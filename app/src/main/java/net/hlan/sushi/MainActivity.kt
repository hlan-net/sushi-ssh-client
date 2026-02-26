package net.hlan.sushi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val geminiSettings by lazy { GeminiSettings(this) }
    private val geminiClient by lazy { GeminiClient(this, geminiSettings) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val driveLogUploader by lazy { DriveLogUploader(this) }
    private val consoleLogRepository by lazy { ConsoleLogRepository(this) }
    private val sshSettings by lazy { SshSettings(this) }
    private val db by lazy { PhraseDatabaseHelper.getInstance(this) }

    private var sshClient: SshClient? = null
    private var isConnecting = false
    private var activeConfig: SshConnectionConfig? = null
    private var lastConnectFailed = false
    private var isCommandRunning = false

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

        binding.geminiPromptText.text = voiceText
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
            handleSessionAction()
        }

        binding.runCommandButton.setOnClickListener {
            handleRunCommand()
        }

        binding.phrasesButton.setOnClickListener {
            showPhrasesDialog()
        }

        binding.clearLogButton.setOnClickListener {
            clearSessionLog()
        }

        binding.copyLogButton.setOnClickListener {
            copySessionLog()
        }

        binding.geminiVoiceButton.setOnClickListener {
            handleGeminiVoice()
        }

        binding.geminiSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateGeminiState()
        refreshSessionLog()
        updateSessionUi()
    }

    override fun onResume() {
        super.onResume()
        updateGeminiState()
        updateSessionUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        sshClient?.disconnect()
        sshClient = null
    }

    private fun updateGeminiState() {
        binding.geminiStatusText.text = when {
            !geminiSettings.isEnabled() -> getString(R.string.gemini_status_disabled)
            geminiSettings.getApiKey().isBlank() -> getString(R.string.gemini_status_missing_key)
            else -> getString(R.string.gemini_status_ready)
        }
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
        binding.geminiProgressBar.visibility = View.VISIBLE
        binding.geminiOutputText.text = getString(R.string.gemini_output_waiting)

        Thread {
            val result = geminiClient.generateCommand(voiceText)
            runOnUiThread {
                binding.geminiProgressBar.visibility = View.GONE
                binding.geminiOutputText.text = result.message
            }
        }.start()
    }

    private fun handleRunCommand() {
        if (isConnecting) {
            Toast.makeText(this, getString(R.string.session_status_connecting), Toast.LENGTH_SHORT).show()
            return
        }

        if (sshClient?.isConnected() != true) {
            Toast.makeText(this, getString(R.string.session_command_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        val command = binding.commandInput.text?.toString()?.trim().orEmpty()
        if (command.isBlank()) {
            Toast.makeText(this, getString(R.string.session_command_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val client = sshClient
        if (client == null) {
            Toast.makeText(this, getString(R.string.session_command_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        isCommandRunning = true
        binding.commandInput.setText("")
        appendSessionLog(getString(R.string.session_command_prefix, command))
        updateSessionUi()

        Thread {
            val result = client.sendCommand(command)

            runOnUiThread {
                isCommandRunning = false
                if (!result.success) {
                    appendSessionLog(getString(R.string.session_command_failed, result.message))
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
                updateSessionUi()
            }
        }.start()
    }

    private fun showPhrasesDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val phrases = db.getAllPhrases()
            withContext(Dispatchers.Main) {
                if (phrases.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.phrases_empty_toast, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, PhrasesActivity::class.java))
                    return@withContext
                }

                val names = phrases.map { it.name }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.phrases_title)
                    .setItems(names) { _, which ->
                        binding.commandInput.setText(phrases[which].command)
                    }
                    .setPositiveButton(R.string.action_manage) { _, _ ->
                        startActivity(Intent(this@MainActivity, PhrasesActivity::class.java))
                    }
                    .setNegativeButton(R.string.phrase_cancel, null)
                    .show()
            }
        }
    }

    private fun handleSessionAction() {
        if (isConnecting) {
            return
        }

        if (sshClient?.isConnected() == true) {
            disconnectSession()
            return
        }

        val config = sshSettings.getConfigOrNull()
        if (config == null) {
            Toast.makeText(this, getString(R.string.ssh_missing_config), Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        activeConfig = config
        isConnecting = true
        lastConnectFailed = false
        updateSessionUi()
        val connectStartedAt = System.currentTimeMillis()
        appendSessionLog(
            getString(
                R.string.session_connect_attempt_start,
                config.displayTarget(),
                formatTimestamp(connectStartedAt)
            )
        )

        Thread {
            val client = SshClient(config)
            val result = client.connect { line ->
                appendSessionLog(line)
            }
            val elapsedMs = System.currentTimeMillis() - connectStartedAt
            runOnUiThread {
                isConnecting = false
                if (result.success) {
                    sshClient = client
                    lastConnectFailed = false
                    appendSessionLog(getString(R.string.session_connect_attempt_success, elapsedMs))
                    appendSessionLog(
                        getString(
                            R.string.session_started_at,
                            formatTimestamp(System.currentTimeMillis())
                        )
                    )
                    appendSessionLog(getString(R.string.session_connected_to, config.displayTarget()))
                } else {
                    sshClient = null
                    lastConnectFailed = true
                    appendSessionLog(
                        getString(R.string.session_connect_attempt_failed, elapsedMs, result.message)
                    )
                    appendDebugInfoIfMissing()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
                updateSessionUi()
            }
        }.start()
    }

    private fun disconnectSession() {
        val target = activeConfig?.displayTarget()
        sshClient?.disconnect()
        sshClient = null
        lastConnectFailed = false
        activeConfig = null
        appendSessionLog(
            if (target.isNullOrBlank()) {
                getString(R.string.session_disconnected)
            } else {
                getString(R.string.session_disconnected_from, target)
            }
        )
        updateSessionUi()
        attemptDriveLogUpload()
    }

    private fun appendSessionLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendSessionLog(message) }
            return
        }

        consoleLogRepository.appendLine(message)
        refreshSessionLog()
    }

    private fun refreshSessionLog() {
        val log = consoleLogRepository.getLog()
        binding.sessionLogText.text = if (log.isBlank()) {
            getString(R.string.session_log_placeholder)
        } else {
            log
        }
    }

    private fun clearSessionLog() {
        consoleLogRepository.clear()
        refreshSessionLog()
        Toast.makeText(this, getString(R.string.session_log_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun copySessionLog() {
        val log = consoleLogRepository.getLog()
        if (log.isBlank()) {
            Toast.makeText(this, getString(R.string.session_log_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        val reportLog = buildReportLog(log)
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH Session Log", reportLog))
        Toast.makeText(this, getString(R.string.session_log_copied), Toast.LENGTH_SHORT).show()
    }

    private fun updateSessionUi() {
        val isConnected = sshClient?.isConnected() == true
        val statusLabel = when {
            isConnecting -> getString(R.string.session_status_connecting)
            isConnected -> getString(R.string.session_status_connected)
            lastConnectFailed -> getString(R.string.session_status_failed)
            else -> getString(R.string.session_status_disconnected)
        }

        val helperText = when {
            isConnecting -> getString(R.string.status_helper_connecting)
            isConnected -> getString(R.string.status_helper_connected)
            lastConnectFailed -> getString(R.string.status_helper_failed)
            else -> getString(R.string.status_helper_disconnected)
        }

        binding.statusText.text = helperText
        binding.sessionStatusText.text = statusLabel

        val displayTarget = if (isConnected || isConnecting) {
            activeConfig?.displayTarget()
        } else {
            sshSettings.getConfigOrNull()?.displayTarget()
        }
        binding.sessionTargetText.text = if (displayTarget.isNullOrBlank()) {
            getString(R.string.session_target_empty)
        } else {
            getString(R.string.session_target, displayTarget)
        }

        binding.startSessionButton.isEnabled = !isConnecting
        binding.startSessionButton.text = when {
            isConnecting -> getString(R.string.session_status_connecting)
            isConnected -> getString(R.string.action_end_session)
            else -> getString(R.string.action_start_session)
        }

        binding.commandInputLayout.isEnabled = isConnected && !isConnecting
        binding.runCommandButton.isEnabled = isConnected && !isConnecting && !isCommandRunning
        binding.runCommandButton.text = if (isCommandRunning) {
            getString(R.string.action_run_command_running)
        } else {
            getString(R.string.action_run_command)
        }
    }

    private fun attemptDriveLogUpload() {
        if (!driveLogSettings.isAlwaysSaveEnabled()) {
            return
        }

        val account = driveAuthManager.getSignedInAccount()
        if (account == null) {
            Toast.makeText(this, getString(R.string.drive_upload_disabled), Toast.LENGTH_SHORT).show()
            return
        }

        val log = consoleLogRepository.getLog()
        if (log.isBlank()) {
            return
        }

        driveLogUploader.uploadLog(account, log) { result ->
            runOnUiThread {
                val message = if (result.success) {
                    getString(R.string.drive_upload_success)
                } else {
                    getString(R.string.drive_upload_failed)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun appendDebugInfoIfMissing() {
        val header = getString(R.string.debug_info_header)
        if (consoleLogRepository.getLog().contains(header)) {
            return
        }
        appendSessionLog(buildDebugInfoBlock())
    }

    private fun buildReportLog(log: String): String {
        val header = getString(R.string.debug_info_header)
        if (log.contains(header)) {
            return log
        }

        val debugInfo = buildDebugInfoBlock()
        return if (log.isBlank()) {
            debugInfo
        } else {
            "$log\n\n$debugInfo"
        }
    }

    private fun buildDebugInfoBlock(): String {
        val androidVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
            ?: getString(R.string.debug_info_unknown)
        val appVersion = getAppVersionInfo()
        val buildType = if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "debug"
        } else {
            "release"
        }
        val locale = Locale.getDefault().toLanguageTag()
        val timeZone = TimeZone.getDefault().id

        val lines = listOf(
            getString(R.string.debug_info_header),
            getString(R.string.debug_info_app_version, appVersion.name, appVersion.code),
            getString(R.string.debug_info_build_type, buildType),
            getString(R.string.debug_info_android, androidVersion, Build.VERSION.SDK_INT),
            getString(
                R.string.debug_info_device,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.DEVICE
            ),
            getString(R.string.debug_info_locale, locale),
            getString(R.string.debug_info_timezone, timeZone)
        )

        return lines.joinToString("\n")
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

    private fun getAppVersionInfo(): AppVersionInfo {
        return runCatching {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.debug_info_unknown)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            AppVersionInfo(versionName, versionCode.toString())
        }.getOrElse {
            AppVersionInfo(getString(R.string.debug_info_unknown), getString(R.string.debug_info_unknown))
        }
    }

    private data class AppVersionInfo(
        val name: String,
        val code: String
    )
}
