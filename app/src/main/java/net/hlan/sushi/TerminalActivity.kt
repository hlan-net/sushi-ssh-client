package net.hlan.sushi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityTerminalBinding

class TerminalActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTerminalBinding
    private val sshSettings by lazy { SshSettings(this) }
    private val appThemeSettings by lazy { AppThemeSettings(this) }
    private val terminalLogRepository by lazy { TerminalLogRepository(this) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val driveLogUploader by lazy { DriveLogUploader(this) }
    private val phraseDb by lazy { PhraseDatabaseHelper.getInstance(this) }

    private var sshClient: TerminalBackend? = null
    private var isConnecting = false
    private var isRetrying = false
    private var didLoseConnection = false
    private var lastConnectFailure: ConnectFailure? = null
    private var lastRawInput: String = ""
    private var lastRawInputAtMs: Long = 0L
    private var lastPrintableChunk: String = ""
    private var lastPrintableChunkAtMs: Long = 0L
    private val connectionMonitorHandler = Handler(Looper.getMainLooper())
    private val connectionMonitorRunnable = Runnable {
        monitorConnection()
    }

    private fun monitorConnection() {
        val client = sshClient
        if (!isConnecting && client != null && !client.isConnected()) {
            handleUnexpectedDisconnect()
        }
        connectionMonitorHandler.postDelayed(connectionMonitorRunnable, CONNECTION_MONITOR_INTERVAL_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.terminalOutputText.textSize = appThemeSettings.getTerminalFontSize().sp

        binding.terminalOutputText.onSizeChangedListener = { col, row, wp, hp ->
            val client = sshClient
            if (client != null) {
                lifecycleScope.launch(Dispatchers.IO) { client.resizePty(col, row, wp, hp) }
            }
        }

        binding.terminalConnectButton.setOnClickListener {
            if (sshClient?.isConnected() == true) {
                disconnectTerminal()
            } else {
                connectTerminal()
            }
        }

        binding.terminalEnterButton.setOnClickListener {
            sendRaw("\n")
        }
        binding.terminalTabButton.setOnClickListener {
            sendRaw("\t")
        }
        binding.terminalBackspaceButton.setOnClickListener {
            sendRaw("\b")
        }
        binding.terminalCtrlCButton.setOnClickListener {
            sshClient?.sendCtrlC()
        }
        binding.terminalCtrlDButton.setOnClickListener {
            sshClient?.sendCtrlD()
        }

        binding.terminalPhrasesButton.setOnClickListener {
            showPhrasePicker()
        }

        binding.terminalOutputText.onInputText = { text ->
            sendRaw(text)
        }

        updateUi()
        connectionMonitorHandler.postDelayed(connectionMonitorRunnable, CONNECTION_MONITOR_INTERVAL_MS)

        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true) {
            connectTerminal()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionMonitorHandler.removeCallbacks(connectionMonitorRunnable)
        disconnectTerminal()
    }

    private fun connectTerminal() {
        if (isConnecting) {
            return
        }
        val config = sshSettings.getConfigOrNull()
        if (config == null) {
            Toast.makeText(this, getString(R.string.ssh_missing_config), Toast.LENGTH_SHORT).show()
            return
        }

        isConnecting = true
        isRetrying = false
        didLoseConnection = false
        updateUi()
        binding.terminalOutputText.appendLog(getString(R.string.terminal_connect_attempt_log))

        lifecycleScope.launch(Dispatchers.IO) {
            val backend: TerminalBackend = when (config.kind) {
                HostKind.LOCAL -> LocalShellBackend(applicationContext)
                HostKind.SSH -> SshClient(config)
            }

            val firstAttempt = connectWith(backend, config)
            var client = firstAttempt.first
            var result = firstAttempt.second

            if (!result.success && config.kind == HostKind.SSH && result.reason.isRetryable) {
                withContext(Dispatchers.Main) {
                    isRetrying = true
                    updateUi()
                    binding.terminalOutputText.appendLog(
                        getString(R.string.terminal_connect_retry_log, CONNECT_RETRY_DELAY_MS)
                    )
                }

                delay(CONNECT_RETRY_DELAY_MS)

                val retryBackend: TerminalBackend = SshClient(config)
                val secondAttempt = connectWith(retryBackend, config)
                client = secondAttempt.first
                result = secondAttempt.second
            }

            withContext(Dispatchers.Main) {
                isConnecting = false
                isRetrying = false
                if (result.success) {
                    sshClient = client
                    didLoseConnection = false
                    lastConnectFailure = null
                    hideErrorBanner()
                    binding.terminalOutputText.appendLog(getString(R.string.session_connected_to, config.displayTarget()))
                    binding.terminalOutputText.requestFocus()

                    // Notify MainActivity that a terminal session is active
                    TerminalSessionHolder.setActiveConnection(client, config)
                } else {
                    client.disconnect()
                    lastConnectFailure = result.reason
                    binding.terminalOutputText.appendLog(getString(R.string.terminal_connect_failed_log, result.message))
                    showErrorBanner(result.reason, result.message, config)
                }
                updateUi()
            }
        }
    }

    private fun showErrorBanner(reason: ConnectFailure, rawMessage: String, config: SshConnectionConfig) {
        val message = when (reason) {
            ConnectFailure.NETWORK -> getString(R.string.connect_error_network)
            ConnectFailure.TIMEOUT -> getString(R.string.connect_error_timeout)
            ConnectFailure.AUTH_KEY -> getString(R.string.connect_error_auth_key)
            ConnectFailure.AUTH_PASSWORD -> getString(R.string.connect_error_auth_password)
            ConnectFailure.HOST_KEY_MISMATCH -> getString(R.string.connect_error_host_key_mismatch)
            ConnectFailure.JUMP_FAILED -> getString(R.string.connect_error_jump_failed)
            ConnectFailure.CHANNEL_FAILED -> getString(R.string.connect_error_channel_failed)
            ConnectFailure.UNKNOWN -> getString(R.string.connect_error_unknown, rawMessage)
        }
        binding.terminalErrorBannerText.text = message
        binding.terminalErrorBanner.visibility = android.view.View.VISIBLE

        val actionButton = binding.terminalErrorBannerAction
        when (reason) {
            ConnectFailure.NETWORK, ConnectFailure.TIMEOUT, ConnectFailure.UNKNOWN -> {
                actionButton.setText(R.string.action_retry)
                actionButton.visibility = android.view.View.VISIBLE
                actionButton.setOnClickListener { hideErrorBanner(); connectTerminal() }
            }
            ConnectFailure.AUTH_KEY -> {
                actionButton.setText(R.string.connect_error_action_try_password)
                actionButton.visibility = android.view.View.VISIBLE
                actionButton.setOnClickListener {
                    hideErrorBanner()
                    startActivity(Intent(this, HostEditActivity::class.java).putExtra(HostEditActivity.EXTRA_HOST_ID, config.id))
                }
            }
            ConnectFailure.AUTH_PASSWORD -> {
                actionButton.setText(R.string.connect_error_action_edit_credentials)
                actionButton.visibility = android.view.View.VISIBLE
                actionButton.setOnClickListener {
                    hideErrorBanner()
                    startActivity(Intent(this, HostEditActivity::class.java).putExtra(HostEditActivity.EXTRA_HOST_ID, config.id))
                }
            }
            ConnectFailure.JUMP_FAILED -> {
                actionButton.setText(R.string.connect_error_action_edit_jump_host)
                actionButton.visibility = android.view.View.VISIBLE
                actionButton.setOnClickListener {
                    hideErrorBanner()
                    startActivity(Intent(this, HostEditActivity::class.java).putExtra(HostEditActivity.EXTRA_HOST_ID, config.id))
                }
            }
            ConnectFailure.HOST_KEY_MISMATCH -> {
                actionButton.visibility = android.view.View.GONE
            }
            ConnectFailure.CHANNEL_FAILED -> {
                actionButton.visibility = android.view.View.GONE
            }
        }
        binding.terminalErrorBannerDismiss.setOnClickListener { hideErrorBanner() }
    }

    private fun hideErrorBanner() {
        binding.terminalErrorBanner.visibility = android.view.View.GONE
    }

    private fun disconnectTerminal() {
        saveTerminalLog()
        sshClient?.disconnect()
        sshClient = null
        isConnecting = false
        
        // Notify MainActivity that the terminal session is gone
        TerminalSessionHolder.clearActiveConnection()
        didLoseConnection = false
        updateUi()
    }

    private fun handleUnexpectedDisconnect() {
        saveTerminalLog()
        sshClient?.disconnect()
        sshClient = null
        didLoseConnection = true
        binding.terminalOutputText.appendLog(getString(R.string.terminal_connection_lost_log))
        Toast.makeText(this, getString(R.string.terminal_connection_lost_toast), Toast.LENGTH_SHORT).show()
        updateUi()
    }

    private fun saveTerminalLog() {
        val logContent = binding.terminalOutputText.getRawText()
        if (logContent.isBlank()) {
            return
        }
        terminalLogRepository.saveLog(logContent)
        uploadLogToDriveIfEnabled(logContent)
    }

    private fun uploadLogToDriveIfEnabled(logContent: String) {
        if (!driveLogSettings.isAlwaysSaveEnabled()) {
            return
        }
        val account = driveAuthManager.getSignedInAccount() ?: return
        driveLogUploader.uploadLog(account, logContent, DriveLogUploader.LogType.TERMINAL) { result ->
            val message = if (result.success) {
                getString(R.string.drive_upload_success)
            } else {
                getString(R.string.drive_upload_failed_detail, result.message)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendRaw(input: String) {
        val client = sshClient
        if (client?.isConnected() != true || isConnecting) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (input == "\n" && lastRawInput == "\n" && now - lastRawInputAtMs < INPUT_DEDUP_WINDOW_MS) {
            return
        }

        if (shouldDropDuplicatePrintableChunk(input, now)) {
            return
        }

        lastRawInput = input
        lastRawInputAtMs = now

        Thread {
            val result = client.sendText(input)
            if (!result.success) {
                runOnUiThread {
                    Toast.makeText(this@TerminalActivity, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun shouldDropDuplicatePrintableChunk(input: String, now: Long): Boolean {
        if (input.length < 2) {
            return false
        }

        if (input.any { it == '\n' || it == '\t' || it == '\b' || it.code < 0x20 }) {
            return false
        }

        val previous = lastPrintableChunk
        val isImmediateDuplicate = now - lastPrintableChunkAtMs < INPUT_DEDUP_WINDOW_MS &&
            previous.isNotEmpty() &&
            input.equals(previous, ignoreCase = true)

        lastPrintableChunk = input
        lastPrintableChunkAtMs = now
        return isImmediateDuplicate
    }

    private fun updateUi() {
        val connected = sshClient?.isConnected() == true
        binding.terminalStatusText.text = when {
            isRetrying -> getString(R.string.terminal_status_retrying)
            isConnecting -> getString(R.string.terminal_status_connecting)
            connected -> getString(R.string.terminal_status_connected)
            didLoseConnection -> getString(R.string.terminal_status_reconnect_needed)
            else -> getString(R.string.terminal_status_disconnected)
        }
        val hostLabel = sshSettings.getConfigOrNull()?.displayTarget()
        binding.terminalConnectButton.text = when {
            connected && hostLabel != null -> getString(R.string.action_end_session_host, hostLabel)
            connected -> getString(R.string.action_end_session)
            didLoseConnection -> getString(R.string.action_reconnect_session)
            hostLabel != null -> getString(R.string.action_start_session_host, hostLabel)
            else -> getString(R.string.action_start_session)
        }
        binding.terminalConnectButton.isEnabled = !isConnecting

        val canInput = connected && !isConnecting
        binding.terminalOutputText.isEnabled = canInput
        binding.terminalEnterButton.isEnabled = canInput
        binding.terminalTabButton.isEnabled = canInput
        binding.terminalBackspaceButton.isEnabled = canInput
        binding.terminalCtrlCButton.isEnabled = canInput
        binding.terminalCtrlDButton.isEnabled = canInput
        binding.terminalPhrasesButton.isEnabled = canInput
    }

    private fun connectWith(backend: TerminalBackend, config: SshConnectionConfig): Pair<TerminalBackend, SshConnectResult> {
        val result = backend.connect(
            streamMode = true,
            onLine = { line ->
                runOnUiThread {
                    binding.terminalOutputText.appendLog(line)
                }
            },
            onConnectionClosed = {
                runOnUiThread {
                    if (!isConnecting && sshClient != null) {
                        handleUnexpectedDisconnect()
                    }
                }
            }
        )
        return backend to result
    }

    private fun showPhrasePicker() {
        PhrasePickerHelper.showPicker(this, phraseDb) { phrase ->
            binding.terminalOutputText.appendLog(getString(R.string.phrase_sent_log, phrase.name))
            sendRaw(phrase.command + "\n")
        }
    }

    companion object {
        private const val EXTRA_AUTO_CONNECT = "extra_auto_connect"
        private const val CONNECTION_MONITOR_INTERVAL_MS = 1500L
        private const val CONNECT_RETRY_DELAY_MS = 1200L
        private const val INPUT_DEDUP_WINDOW_MS = 150L

        fun createIntent(context: Context, autoConnect: Boolean): Intent {
            return Intent(context, TerminalActivity::class.java).putExtra(EXTRA_AUTO_CONNECT, autoConnect)
        }
    }
}
