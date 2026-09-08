package net.hlan.sushi

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivitySftpDownloadBinding
import net.hlan.sushi.databinding.DialogRemotePathBinding
import java.io.File

/**
 * Downloads a single file from a remote host to the phone over SFTP, then offers to open or
 * share it. The in-app counterpart to [ShareActivity] (which uploads via the system Share sheet).
 *
 * Launched from the terminal tab. Picks the host the same way [ShareActivity] does, opens its
 * own SFTP session via [SshClient.sftpDownload], and stores the file under the app cache so it
 * can be handed to other apps through a [FileProvider] `content://` URI.
 */
class SftpDownloadActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySftpDownloadBinding
    private val sshSettings by lazy { SshSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeSettings(this).applyAccentOverlay(this)
        binding = ActivitySftpDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pickHostThenDownload()
    }

    private fun pickHostThenDownload() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hosts = sshSettings.getHosts()
            withContext(Dispatchers.Main) {
                when {
                    hosts.isEmpty() -> {
                        Toast.makeText(
                            this@SftpDownloadActivity,
                            getString(R.string.share_no_hosts),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    hosts.size == 1 -> showPathDialog(hosts[0])
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
            .setTitle(R.string.download_select_host_title)
            .setItems(labels) { _, which ->
                showPathDialog(hosts[which])
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showPathDialog(host: SshConnectionConfig) {
        val dialogBinding = DialogRemotePathBinding.inflate(layoutInflater)
        val inputLayout = dialogBinding.remotePathLayout
        val input = dialogBinding.remotePathInput
        inputLayout.hint = getString(R.string.download_remote_path_label)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_from_host, host.displayTarget()))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.download_button, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val remotePath = input.text?.toString()?.trim().orEmpty()
                if (remotePath.isBlank()) {
                    inputLayout.error = getString(R.string.share_path_required)
                    return@setOnClickListener
                }
                inputLayout.error = null
                dialog.dismiss()
                performDownload(host, remotePath)
            }
        }

        dialog.show()
    }

    private fun performDownload(host: SshConnectionConfig, remotePath: String) {
        binding.downloadProgressBar.visibility = View.VISIBLE
        binding.downloadStatusText.text = getString(R.string.download_downloading)

        val filename = safeFilename(remotePath)

        lifecycleScope.launch(Dispatchers.IO) {
            val resolvedConfig = sshSettings.resolveJumpServer(
                host.copy(privateKey = sshSettings.getPrivateKey())
            )
            val client = SshClient(
                resolvedConfig,
                DialogUserInfo(
                    this@SftpDownloadActivity,
                    resolvedConfig.displayTarget(),
                    KeyPassphraseCache(this@SftpDownloadActivity)
                ),
                SshKnownHosts.file(this@SftpDownloadActivity)
            )

            val downloadsDir = File(cacheDir, "downloads").apply { mkdirs() }
            val destination = File(downloadsDir, filename)

            val result = runCatching {
                destination.outputStream().use { client.sftpDownload(remotePath, it) }
            }.getOrElse { error ->
                SftpDownloadResult(false, error.message.orEmpty())
            }

            withContext(Dispatchers.Main) {
                binding.downloadProgressBar.visibility = View.GONE
                if (result.success) {
                    binding.downloadStatusText.text =
                        getString(R.string.download_success, destination.name)
                    offerOpenOrShare(destination)
                } else {
                    destination.delete()
                    // With a specific detail, show "Download failed: <detail>"; with none, show
                    // the standalone generic string (not fed into the "…: %s" format, which would
                    // otherwise read "Download failed: Download failed").
                    binding.downloadStatusText.text = result.message.takeIf { it.isNotBlank() }
                        ?.let { getString(R.string.download_failed, it) }
                        ?: getString(R.string.download_failed_generic)
                }
            }
        }
    }

    private fun offerOpenOrShare(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mimeType = mimeTypeFor(file.name)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_complete_title))
            .setMessage(getString(R.string.download_saved_as, file.name))
            .setPositiveButton(R.string.download_open) { _, _ ->
                launchViewer(uri, mimeType)
            }
            .setNeutralButton(R.string.download_share) { _, _ ->
                launchShare(uri, mimeType)
            }
            .setNegativeButton(R.string.download_done) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun launchViewer(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
        }
        launchChooser(intent, uri, getString(R.string.download_open))
    }

    private fun launchShare(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        launchChooser(intent, uri, getString(R.string.download_share))
    }

    /**
     * Launch [intent] through a chooser, granting read access to [uri] reliably. The grant flag
     * and a [ClipData] carrying the URI are set on both the inner intent and the chooser wrapper,
     * since it's the chooser that is actually started — otherwise the selected target can lose the
     * temporary URI grant (notably for ACTION_SEND, where the URI rides in EXTRA_STREAM rather
     * than the intent data). Pre-checking with resolveActivity() is unreliable under Android 11+
     * package visibility, so we just launch and report if nothing can handle it.
     */
    private fun launchChooser(intent: Intent, uri: Uri, title: String) {
        intent.clipData = ClipData.newRawUri(null, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.download_no_viewer, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /**
     * Derive a safe local filename from a remote path. Guards against the last segment being
     * empty or a directory reference (`.` / `..`), which would otherwise resolve to a directory
     * inside the cache and fail; falls back to a fixed default.
     */
    private fun safeFilename(remotePath: String): String {
        val segment = remotePath.trimEnd('/').substringAfterLast('/').trim()
        return if (segment.isEmpty() || segment == "." || segment == "..") "downloaded_file" else segment
    }

    private fun mimeTypeFor(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
