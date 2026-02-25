package net.hlan.sushi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import net.hlan.sushi.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val geminiSettings by lazy { GeminiSettings(this) }
    private val geminiClient by lazy { GeminiClient(this, geminiSettings) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val driveLogUploader by lazy { DriveLogUploader(this) }
    private val consoleLogRepository by lazy { ConsoleLogRepository(this) }
    private val sshSettings by lazy { SshSettings(this) }

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
        appendSessionLog("> $command")
        updateSessionUi()

        Thread {
            val result = client.sendCommand(command)

            runOnUiThread {
                isCommandRunning = false
                if (!result.success) {
                    appendSessionLog("Command failed: ${result.message}")
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
                updateSessionUi()
            }
        }.start()
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
        appendSessionLog("Connecting to ${config.displayTarget()}")

        Thread {
            val client = SshClient(config)
            val result = client.connect { line ->
                appendSessionLog(line)
            }
            runOnUiThread {
                isConnecting = false
                if (result.success) {
                    sshClient = client
                    lastConnectFailed = false
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date())
                    appendSessionLog("Session started at $timestamp")
                    appendSessionLog("Connected to ${config.displayTarget()}")
                } else {
                    sshClient = null
                    lastConnectFailed = true
                    appendSessionLog("Connection failed: ${result.message}")
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
                "Session disconnected"
            } else {
                "Disconnected from $target"
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
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH Session Log", log))
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
}
