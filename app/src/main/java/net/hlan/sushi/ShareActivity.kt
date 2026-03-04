package net.hlan.sushi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityShareBinding
import java.io.ByteArrayInputStream

class ShareActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShareBinding
    private val sshSettings by lazy { SshSettings(this) }

    private sealed class SharePayload {
        data class TextPayload(val text: String) : SharePayload()
        data class FilePayload(val uri: Uri, val filename: String) : SharePayload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val payload = extractPayload()
        if (payload == null) {
            Toast.makeText(this, getString(R.string.share_nothing_to_share), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pickHostThenUpload(payload)
    }

    private fun extractPayload(): SharePayload? {
        if (intent?.action != Intent.ACTION_SEND) return null

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            return SharePayload.TextPayload(text)
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            val filename = resolveFilename(uri)
            return SharePayload.FilePayload(uri, filename)
        }
        return null
    }

    private fun resolveFilename(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        return uri.lastPathSegment ?: "shared_file"
    }

    private fun pickHostThenUpload(payload: SharePayload) {
        lifecycleScope.launch(Dispatchers.IO) {
            val hosts = sshSettings.getHosts()
            withContext(Dispatchers.Main) {
                when {
                    hosts.isEmpty() -> {
                        Toast.makeText(
                            this@ShareActivity,
                            getString(R.string.share_no_hosts),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    hosts.size == 1 -> showPathDialog(hosts[0], payload)
                    else -> showHostPicker(hosts, payload)
                }
            }
        }
    }

    private fun showHostPicker(hosts: List<SshConnectionConfig>, payload: SharePayload) {
        val activeHostId = sshSettings.getActiveHostId()
        val labels = hosts.map { host ->
            if (host.id == activeHostId) {
                getString(R.string.share_host_item_active, host.displayTarget())
            } else {
                host.displayTarget()
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.share_select_host_title)
            .setItems(labels) { _, which ->
                showPathDialog(hosts[which], payload)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showPathDialog(host: SshConnectionConfig, payload: SharePayload) {
        val defaultPath = when (payload) {
            is SharePayload.TextPayload -> "/tmp/shared_text.txt"
            is SharePayload.FilePayload -> "/tmp/${payload.filename}"
        }

        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.share_remote_path_label)
            setPadding(48, 16, 48, 0)
        }
        val input = TextInputEditText(this).apply {
            setText(defaultPath)
        }
        inputLayout.addView(input)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.share_upload_to_host, host.displayTarget()))
            .setView(inputLayout)
            .setPositiveButton(R.string.share_upload_button) { _, _ ->
                val remotePath = input.text?.toString()?.trim().orEmpty()
                if (remotePath.isBlank()) {
                    Toast.makeText(this, getString(R.string.share_path_required), Toast.LENGTH_SHORT).show()
                    finish()
                    return@setPositiveButton
                }
                performUpload(host, remotePath, payload)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun performUpload(
        host: SshConnectionConfig,
        remotePath: String,
        payload: SharePayload
    ) {
        binding.shareProgressBar.visibility = View.VISIBLE
        binding.shareStatusText.text = getString(R.string.share_uploading)

        lifecycleScope.launch(Dispatchers.IO) {
            val resolvedConfig = sshSettings.resolveJumpServer(
                host.copy(privateKey = sshSettings.getPrivateKey())
            )
            val client = SshClient(resolvedConfig)

            val inputStream = when (payload) {
                is SharePayload.TextPayload ->
                    ByteArrayInputStream(payload.text.toByteArray(Charsets.UTF_8))
                is SharePayload.FilePayload ->
                    contentResolver.openInputStream(payload.uri)
            }

            val result = if (inputStream != null) {
                inputStream.use { stream ->
                    client.sftpUpload(remotePath, stream)
                }
            } else {
                SftpUploadResult(false, "Could not read shared content")
            }

            withContext(Dispatchers.Main) {
                binding.shareProgressBar.visibility = View.GONE
                if (result.success) {
                    Toast.makeText(
                        this@ShareActivity,
                        getString(R.string.share_upload_success, remotePath),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                } else {
                    binding.shareStatusText.text = getString(
                        R.string.share_upload_failed, result.message
                    )
                }
            }
        }
    }
}
