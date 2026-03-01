package net.hlan.sushi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
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

    private var sshClient: SshClient? = null
    private var isConnecting = false
    private var isRetrying = false
    private var didLoseConnection = false
    private var lastRawInput: String = ""
    private var lastRawInputAtMs: Long = 0L
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

        binding.terminalOutputText.onSizeChangedListener = { col, row, wp, hp ->
            sshClient?.resizePty(col, row, wp, hp)
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
            val firstAttempt = connectWithClient(config)
            var client = firstAttempt.first
            var result = firstAttempt.second

            if (!result.success) {
                withContext(Dispatchers.Main) {
                    isRetrying = true
                    updateUi()
                    binding.terminalOutputText.appendLog(
                        getString(R.string.terminal_connect_retry_log, CONNECT_RETRY_DELAY_MS)
                    )
                }

                delay(CONNECT_RETRY_DELAY_MS)

                val secondAttempt = connectWithClient(config)
                client = secondAttempt.first
                result = secondAttempt.second
            }

            withContext(Dispatchers.Main) {
                isConnecting = false
                isRetrying = false
                if (result.success) {
                    sshClient = client
                    didLoseConnection = false
                    binding.terminalOutputText.appendLog(getString(R.string.session_connected_to, config.displayTarget()))
                    binding.terminalOutputText.requestFocus()
                } else {
                    client.disconnect()
                    binding.terminalOutputText.appendLog(getString(R.string.terminal_connect_failed_log, result.message))
                    Toast.makeText(this@TerminalActivity, result.message, Toast.LENGTH_SHORT).show()
                }
                updateUi()
            }
        }
    }

    private fun disconnectTerminal() {
        sshClient?.disconnect()
        sshClient = null
        isConnecting = false
        didLoseConnection = false
        updateUi()
    }

    private fun handleUnexpectedDisconnect() {
        sshClient?.disconnect()
        sshClient = null
        didLoseConnection = true
        binding.terminalOutputText.appendLog(getString(R.string.terminal_connection_lost_log))
        Toast.makeText(this, getString(R.string.terminal_connection_lost_toast), Toast.LENGTH_SHORT).show()
        updateUi()
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

    private fun updateUi() {
        val connected = sshClient?.isConnected() == true
        binding.terminalStatusText.text = when {
            isRetrying -> getString(R.string.terminal_status_retrying)
            isConnecting -> getString(R.string.terminal_status_connecting)
            connected -> getString(R.string.terminal_status_connected)
            didLoseConnection -> getString(R.string.terminal_status_reconnect_needed)
            else -> getString(R.string.terminal_status_disconnected)
        }
        binding.terminalConnectButton.text = if (connected) {
            getString(R.string.action_end_session)
        } else if (didLoseConnection) {
            getString(R.string.action_reconnect_session)
        } else {
            getString(R.string.action_start_session)
        }
        binding.terminalConnectButton.isEnabled = !isConnecting

        val canInput = connected && !isConnecting
        binding.terminalOutputText.isEnabled = canInput
        binding.terminalEnterButton.isEnabled = canInput
        binding.terminalTabButton.isEnabled = canInput
        binding.terminalBackspaceButton.isEnabled = canInput
        binding.terminalCtrlCButton.isEnabled = canInput
        binding.terminalCtrlDButton.isEnabled = canInput
    }

    private fun connectWithClient(config: SshConnectionConfig): Pair<SshClient, SshConnectResult> {
        val client = SshClient(config)
        val result = client.connect(streamMode = true, onLine = { line ->
            runOnUiThread {
                binding.terminalOutputText.appendLog(line)
            }
        })
        return client to result
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
