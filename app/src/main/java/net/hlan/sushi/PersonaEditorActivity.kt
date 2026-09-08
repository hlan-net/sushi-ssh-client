package net.hlan.sushi

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityPersonaEditorBinding

/**
 * Reads, edits, and writes the target-side persona file `~/.config/sushi/SUSHI.md` without
 * leaving the app. Previously the only way to customise the persona was a separate SSH terminal.
 *
 * Each operation (load / save / reset) opens its own short-lived connection — the same stateless
 * approach as [ShareActivity]/[SftpDownloadActivity] — so the connection is never held open while
 * the user is editing. File content is transferred base64-encoded over [SshClient.execCommand] to
 * avoid shell-escaping pitfalls with arbitrary Markdown.
 */
class PersonaEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPersonaEditorBinding
    private val sshSettings by lazy { SshSettings(this) }

    private var selectedHost: SshConnectionConfig? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeSettings(this).applyAccentOverlay(this)
        binding = ActivityPersonaEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.personaSaveButton.setOnClickListener { onSaveClicked() }
        binding.personaResetButton.setOnClickListener { onResetClicked() }
        setEditingEnabled(false)

        pickHostThenLoad()
    }

    private fun pickHostThenLoad() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hosts = sshSettings.getHosts()
            withContext(Dispatchers.Main) {
                when {
                    hosts.isEmpty() -> {
                        Toast.makeText(this@PersonaEditorActivity, R.string.share_no_hosts, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    hosts.size == 1 -> loadPersona(hosts[0])
                    else -> showHostPicker(hosts)
                }
            }
        }
    }

    private fun showHostPicker(hosts: List<SshConnectionConfig>) {
        val activeHostId = sshSettings.getActiveHostId()
        val labels = hosts.map { host ->
            if (host.id == activeHostId) {
                getString(R.string.share_host_item_active, host.displayTarget())
            } else {
                host.displayTarget()
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.persona_editor_select_host_title)
            .setItems(labels) { _, which -> loadPersona(hosts[which]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun loadPersona(host: SshConnectionConfig) {
        selectedHost = host
        binding.personaEditorStatus.text = getString(R.string.persona_editor_loading, host.displayTarget())
        setBusy(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = withConnection(host) { client ->
                client.execCommand(READ_COMMAND)
            }
            withContext(Dispatchers.Main) {
                setBusy(false)
                when (outcome) {
                    is ConnResult.Failure -> showConnectionError(outcome.message) { loadPersona(host) }
                    is ConnResult.Success -> {
                        val result = outcome.value
                        if (!result.success) {
                            showConnectionError(result.message) { loadPersona(host) }
                            return@withContext
                        }
                        val content = if (result.message.contains(MISSING_MARKER)) {
                            "" // No persona yet — start from a blank editor.
                        } else {
                            decodeBase64(result.message)
                        }
                        if (content == null) {
                            // The remote output wasn't decodable base64. Do NOT populate an empty
                            // editor — saving that would overwrite SUSHI.md with an empty file.
                            showConnectionError(
                                getString(R.string.persona_editor_decode_error)
                            ) { loadPersona(host) }
                            return@withContext
                        }
                        binding.personaEditorInput.setText(content)
                        binding.personaEditorStatus.text =
                            getString(R.string.persona_editor_editing, host.displayTarget())
                        setEditingEnabled(true)
                    }
                }
            }
        }
    }

    private fun onSaveClicked() {
        val host = selectedHost ?: return
        if (busy) return
        val content = binding.personaEditorInput.text?.toString().orEmpty()

        val validation = PersonaValidator.validate(content)
        val message = buildString {
            append(getString(R.string.persona_editor_confirm_overwrite, host.displayTarget()))
            if (validation.isEmpty) {
                append("\n\n")
                append(getString(R.string.persona_editor_warn_empty))
            } else if (validation.missingSections.isNotEmpty()) {
                append("\n\n")
                append(
                    getString(
                        R.string.persona_editor_warn_missing_sections,
                        validation.missingSections.joinToString(", ")
                    )
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.persona_editor_save)
            .setMessage(message)
            .setPositiveButton(R.string.persona_editor_save) { _, _ -> savePersona(host, content) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun savePersona(host: SshConnectionConfig, content: String) {
        setBusy(true)
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "mkdir -p $CONFIG_DIR && printf '%s' '$encoded' | base64 -d > $SUSHI_MD_PATH"

        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = withConnection(host) { client -> client.execCommand(command) }
            withContext(Dispatchers.Main) {
                setBusy(false)
                when (outcome) {
                    is ConnResult.Failure -> showConnectionError(outcome.message, retry = null)
                    is ConnResult.Success -> {
                        if (outcome.value.success) {
                            Toast.makeText(this@PersonaEditorActivity, R.string.persona_editor_saved, Toast.LENGTH_SHORT).show()
                        } else {
                            showConnectionError(outcome.value.message, retry = null)
                        }
                    }
                }
            }
        }
    }

    private fun onResetClicked() {
        val host = selectedHost ?: return
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.persona_editor_reset)
            .setMessage(getString(R.string.persona_editor_confirm_reset, host.displayTarget()))
            .setPositiveButton(R.string.persona_editor_reset) { _, _ -> resetPersona(host) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetPersona(host: SshConnectionConfig) {
        setBusy(true)
        binding.personaEditorStatus.text = getString(R.string.persona_editor_resetting, host.displayTarget())

        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = withConnection(host) { client ->
                client.execCommand(ManagedPlays.buildInitPersonaScript(), timeoutMs = RESET_TIMEOUT_MS)
            }
            withContext(Dispatchers.Main) {
                if (outcome is ConnResult.Success && outcome.value.success) {
                    Toast.makeText(this@PersonaEditorActivity, R.string.persona_editor_reset_done, Toast.LENGTH_SHORT).show()
                    loadPersona(host) // Reload the freshly generated persona.
                } else {
                    setBusy(false)
                    // Restore the editing status so it doesn't stay stuck on "Regenerating…".
                    binding.personaEditorStatus.text =
                        getString(R.string.persona_editor_editing, host.displayTarget())
                    val msg = when (outcome) {
                        is ConnResult.Failure -> outcome.message
                        is ConnResult.Success -> outcome.value.message
                    }
                    showConnectionError(msg, retry = null)
                }
            }
        }
    }

    /**
     * Open a short-lived connection to [host], run [block] against the connected client, and
     * always disconnect. Returns [ConnResult.Failure] if the connection could not be established.
     */
    private fun <T> withConnection(
        host: SshConnectionConfig,
        block: (SshClient) -> T
    ): ConnResult<T> {
        val resolvedConfig = sshSettings.resolveJumpServer(
            host.copy(privateKey = sshSettings.getPrivateKey())
        )
        val client = SshClient(
            resolvedConfig,
            DialogUserInfo(this, resolvedConfig.displayTarget(), KeyPassphraseCache(this)),
            SshKnownHosts.file(this)
        )
        val connect = client.connect(onLine = {})
        if (!connect.success) {
            runCatching { client.disconnect() }
            return ConnResult.Failure(connect.message)
        }
        return try {
            ConnResult.Success(block(client))
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun showConnectionError(message: String, retry: (() -> Unit)?) {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.persona_editor_error_title)
            .setMessage(message)
        if (retry != null) {
            builder.setPositiveButton(R.string.persona_editor_retry) { _, _ -> retry() }
            builder.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.personaEditorProgress.visibility = if (value) View.VISIBLE else View.GONE
        binding.personaSaveButton.isEnabled = !value && binding.personaEditorInput.isEnabled
        binding.personaResetButton.isEnabled = !value && selectedHost != null
    }

    private fun setEditingEnabled(enabled: Boolean) {
        binding.personaEditorInput.isEnabled = enabled
        binding.personaSaveButton.isEnabled = enabled && !busy
        binding.personaResetButton.isEnabled = selectedHost != null && !busy
    }

    /** Decodes remote base64 output, or null when it is not valid base64. */
    private fun decodeBase64(raw: String): String? {
        val cleaned = raw.filterNot { it.isWhitespace() }
        return runCatching {
            String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private sealed class ConnResult<out T> {
        data class Success<T>(val value: T) : ConnResult<T>()
        data class Failure(val message: String) : ConnResult<Nothing>()
    }

    companion object {
        private const val CONFIG_DIR = "~/.config/sushi"
        private const val SUSHI_MD_PATH = "~/.config/sushi/SUSHI.md"
        private const val MISSING_MARKER = "__SUSHI_MD_MISSING__"
        private const val RESET_TIMEOUT_MS = 60_000L
        private const val READ_COMMAND =
            "if [ -f $SUSHI_MD_PATH ]; then base64 $SUSHI_MD_PATH; else echo '$MISSING_MARKER'; fi"
    }
}
