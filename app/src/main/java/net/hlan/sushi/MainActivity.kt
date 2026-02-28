package net.hlan.sushi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityMainBinding
import net.hlan.sushi.databinding.DialogGeminiControlsBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val geminiSettings by lazy { GeminiSettings(this) }
    private val geminiClient by lazy { GeminiClient(this, geminiSettings) }
    private val consoleLogRepository by lazy { ConsoleLogRepository(this) }
    private val sshSettings by lazy { SshSettings(this) }
    private val playDb by lazy { PlayDatabaseHelper.getInstance(this) }

    private var isPlayRunning = false
    private var geminiDialog: AlertDialog? = null
    private var geminiDialogBinding: DialogGeminiControlsBinding? = null
    private var lastGeminiPrompt = ""
    private var lastGeminiOutput = ""
    private var isGeminiRequestRunning = false

    private val voiceResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }

        val voiceText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()

        if (voiceText.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.gemini_output_error), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        lastGeminiPrompt = voiceText
        updateGeminiDialogState()
        requestGeminiCommand(voiceText)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            Toast.makeText(this, getString(R.string.gemini_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startSessionButton.setOnClickListener {
            startActivity(TerminalActivity.createIntent(this, autoConnect = true))
        }

        binding.configureHostButton.setOnClickListener {
            val hosts = sshSettings.getHosts()
            if (hosts.isEmpty()) {
                startActivity(Intent(this, HostEditActivity::class.java))
            } else {
                startActivity(Intent(this, HostsActivity::class.java))
            }
        }

        binding.playsButton.setOnClickListener {
            showPlayHostDialog()
        }

        binding.geminiVoiceButton.setOnClickListener {
            showGeminiDialog()
        }

        binding.geminiSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateGeminiState()
        ManagedPlays.ensure(this, sshSettings.getPublicKey())
        refreshSessionLog()
        updateSessionUi()
        
        val appVersion = AppUtils.getAppVersionInfo(this)
        binding.footerText.text = getString(R.string.placeholder_footer, appVersion.name)
    }

    override fun onResume() {
        super.onResume()
        updateGeminiState()
        updateSessionUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        geminiDialog?.dismiss()
        geminiDialog = null
        geminiDialogBinding = null
    }

    private fun updateGeminiState() {
        val status = when {
            !geminiSettings.isEnabled() -> getString(R.string.gemini_status_disabled)
            geminiSettings.getApiKey().isBlank() -> getString(R.string.gemini_status_missing_key)
            else -> getString(R.string.gemini_status_ready)
        }
        binding.geminiStatusText.text = status
        geminiDialogBinding?.geminiDialogStatusText?.text = status
    }

    private fun handleGeminiVoice() {
        if (!geminiSettings.isEnabled() || geminiSettings.getApiKey().isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.gemini_voice_prompt))
        }
        voiceResultLauncher.launch(intent)
    }

    private fun requestGeminiCommand(voiceText: String) {
        isGeminiRequestRunning = true
        lastGeminiOutput = getString(R.string.gemini_output_waiting)
        updateGeminiDialogState()

        Thread {
            val result = geminiClient.generateCommand(voiceText)
            runOnUiThread {
                isGeminiRequestRunning = false
                lastGeminiOutput = result.message
                updateGeminiDialogState()
            }
        }.start()
    }

    private fun showGeminiDialog() {
        if (geminiDialog?.isShowing == true) {
            return
        }

        val dialogBinding = DialogGeminiControlsBinding.inflate(layoutInflater)
        geminiDialogBinding = dialogBinding

        dialogBinding.geminiDialogVoiceButton.setOnClickListener {
            handleGeminiVoice()
        }
        dialogBinding.geminiDialogSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.phrase_cancel, null)
            .create()
        dialog.setOnDismissListener {
            geminiDialog = null
            geminiDialogBinding = null
        }
        geminiDialog = dialog
        updateGeminiState()
        updateGeminiDialogState()
        dialog.show()
    }

    private fun updateGeminiDialogState() {
        val dialogBinding = geminiDialogBinding ?: return
        dialogBinding.geminiDialogPromptText.text = if (lastGeminiPrompt.isBlank()) {
            getString(R.string.gemini_prompt_placeholder)
        } else {
            lastGeminiPrompt
        }
        dialogBinding.geminiDialogOutputText.text = if (lastGeminiOutput.isBlank()) {
            getString(R.string.gemini_output_placeholder)
        } else {
            lastGeminiOutput
        }
        dialogBinding.geminiDialogProgressBar.visibility = if (isGeminiRequestRunning) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showPlayHostDialog() {
        if (isPlayRunning) {
            Toast.makeText(this, getString(R.string.play_status_running_toast), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val plays = playDb.getAllPlays()
            val hosts = sshSettings.getHosts()
            val activeHostId = sshSettings.getActiveHostId()

            withContext(Dispatchers.Main) {
                if (plays.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.plays_empty_toast, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, PlaysActivity::class.java))
                    return@withContext
                }
                if (hosts.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.play_missing_hosts, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    return@withContext
                }

                val hostLabels = hosts.map { host ->
                    if (host.id == activeHostId) {
                        getString(R.string.play_host_item_active, host.displayTarget())
                    } else {
                        getString(R.string.play_host_item, host.displayTarget())
                    }
                }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.play_select_host_title)
                    .setItems(hostLabels) { _, which ->
                        showPlaySelectionDialog(plays, hosts[which])
                    }
                    .setNegativeButton(R.string.phrase_cancel, null)
                    .show()
            }
        }
    }

    private fun showPlaySelectionDialog(plays: List<Play>, host: SshConnectionConfig) {
        val playNames = plays.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.plays_title)
            .setItems(playNames) { _, which ->
                val play = plays[which]
                promptPlayParametersAndRun(play, host)
            }
            .setPositiveButton(R.string.action_manage) { _, _ ->
                startActivity(Intent(this, PlaysActivity::class.java))
            }
            .setNegativeButton(R.string.phrase_cancel, null)
            .show()
    }

    private fun promptPlayParametersAndRun(play: Play, host: SshConnectionConfig) {
        val parameters = PlayParameters.decode(play.parametersJson).ifEmpty {
            PlayParameters.inferFromTemplate(play.scriptTemplate)
        }

        if (parameters.isEmpty()) {
            runPlay(play, host, emptyMap())
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val fields = linkedMapOf<PlayParameter, TextInputEditText>()

        parameters.forEachIndexed { index, parameter ->
            val layout = TextInputLayout(this).apply {
                hint = parameter.label
            }
            val input = TextInputEditText(this).apply {
                inputType = if (parameter.secret) {
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT
                }
            }
            layout.addView(input)
            if (index > 0) {
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 12
                container.addView(layout, params)
            } else {
                container.addView(layout)
            }
            fields[parameter] = input
        }

        val content = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_prompt_title, play.name))
            .setView(content)
            .setPositiveButton(R.string.action_run_play, null)
            .setNegativeButton(R.string.phrase_cancel, null)
            .create()

        dialog.setOnShowListener {
            val runButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            runButton.setOnClickListener {
                val values = mutableMapOf<String, String>()
                var hasError = false
                fields.forEach { (parameter, input) ->
                    val value = input.text?.toString().orEmpty()
                    val layout = input.parent.parent as? TextInputLayout
                    layout?.error = null
                    if (parameter.required && value.isBlank()) {
                        layout?.error = getString(R.string.play_parameter_required)
                        hasError = true
                    } else {
                        values[parameter.key] = value
                    }
                }
                if (hasError) {
                    return@setOnClickListener
                }
                dialog.dismiss()
                runPlay(play, host, values)
            }
        }
        dialog.show()
    }

    private fun runPlay(play: Play, host: SshConnectionConfig, values: Map<String, String>) {
        val config = sshSettings.resolveJumpServer(host.copy(privateKey = sshSettings.getPrivateKey()))
        isPlayRunning = true
        updateSessionUi()
        appendSessionLog(getString(R.string.play_run_started, play.name, config.displayTarget()))

        Thread {
            val result = PlayRunner.execute(
                play = play,
                hostConfig = config,
                values = values,
                onLine = { line -> appendSessionLog("[Play] ${line.trimEnd()}") }
            )

            runOnUiThread {
                isPlayRunning = false
                if (result.success) {
                    appendSessionLog(getString(R.string.play_run_finished, play.name))
                } else {
                    appendSessionLog(getString(R.string.play_run_failed, play.name, result.message))
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
                updateSessionUi()
            }
        }.start()
    }

    private fun appendSessionLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendSessionLog(message) }
            return
        }

        consoleLogRepository.appendLine(message)
        binding.sessionLogText.appendLog(message)
        scrollToSessionLog()
    }

    private fun scrollToSessionLog() {
        binding.mainScrollView.post {
            binding.mainScrollView.smoothScrollTo(0, binding.sessionCard.bottom)
        }
    }

    private fun refreshSessionLog() {
        val log = consoleLogRepository.getLog()
        binding.sessionLogText.clearLog()
        if (log.isBlank()) {
            binding.sessionLogText.appendLog(getString(R.string.session_log_placeholder))
        } else {
            binding.sessionLogText.appendLog(log)
        }
    }

    private fun updateSessionUi() {
        val config = sshSettings.getConfigOrNull()
        val statusLabel = if (isPlayRunning) {
            getString(R.string.session_status_play_running)
        } else {
            getString(R.string.session_status_ready)
        }
        val helperText = if (config == null) {
            getString(R.string.status_helper_disconnected)
        } else if (isPlayRunning) {
            getString(R.string.status_helper_play_running)
        } else {
            getString(R.string.status_helper_ready)
        }

        binding.statusText.text = helperText
        binding.sessionStatusText.text = statusLabel

        val displayTarget = config?.displayTarget()
        binding.sessionTargetText.text = if (displayTarget.isNullOrBlank()) {
            getString(R.string.session_target_empty)
        } else {
            getString(R.string.session_target, displayTarget)
        }

        binding.startSessionButton.isEnabled = config != null
        binding.startSessionButton.text = getString(R.string.action_start_session)
        binding.configureHostButton.visibility = if (config == null) View.VISIBLE else View.GONE

        binding.playsButton.isEnabled = !isPlayRunning
    }
}
