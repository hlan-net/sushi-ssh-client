package net.hlan.sushi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.hlan.sushi.databinding.ActivityTerminalBinding

class TerminalActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTerminalBinding
    private val sshSettings by lazy { SshSettings(this) }

    private var sshClient: SshClient? = null
    private var isConnecting = false
    private var suppressInputWatcher = false

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

        binding.terminalLiveInput.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isSend || isEnter) {
                sendRaw("\n")
                true
            } else {
                false
            }
        }

        binding.terminalLiveInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressInputWatcher) {
                    return
                }
                val chunk = s?.toString().orEmpty()
                if (chunk.isBlank()) {
                    return
                }
                sendRaw(chunk)
                suppressInputWatcher = true
                binding.terminalLiveInput.text?.clear()
                suppressInputWatcher = false
            }
        })

        updateUi()

        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true) {
            connectTerminal()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
        updateUi()

        Thread {
            val client = SshClient(config)
            val result = client.connect { line ->
                runOnUiThread {
                    binding.terminalOutputText.appendLog(line)
                }
            }
            runOnUiThread {
                isConnecting = false
                if (result.success) {
                    sshClient = client
                    binding.terminalOutputText.appendLog(getString(R.string.session_connected_to, config.displayTarget()))
                } else {
                    client.disconnect()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
                updateUi()
            }
        }.start()
    }

    private fun disconnectTerminal() {
        sshClient?.disconnect()
        sshClient = null
        isConnecting = false
        updateUi()
    }

    private fun sendRaw(input: String) {
        val client = sshClient
        if (client?.isConnected() != true || isConnecting) {
            return
        }
        Thread {
            val result = client.sendText(input)
            if (!result.success) {
                runOnUiThread {
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun updateUi() {
        val connected = sshClient?.isConnected() == true
        binding.terminalStatusText.text = when {
            isConnecting -> getString(R.string.terminal_status_connecting)
            connected -> getString(R.string.terminal_status_connected)
            else -> getString(R.string.terminal_status_disconnected)
        }
        binding.terminalConnectButton.text = if (connected) {
            getString(R.string.action_end_session)
        } else {
            getString(R.string.action_start_session)
        }

        val canInput = connected && !isConnecting
        binding.terminalLiveInputLayout.isEnabled = canInput
        binding.terminalLiveInput.isEnabled = canInput
        binding.terminalEnterButton.isEnabled = canInput
        binding.terminalTabButton.isEnabled = canInput
        binding.terminalBackspaceButton.isEnabled = canInput
        binding.terminalCtrlCButton.isEnabled = canInput
        binding.terminalCtrlDButton.isEnabled = canInput
    }

    companion object {
        private const val EXTRA_AUTO_CONNECT = "extra_auto_connect"

        fun createIntent(context: Context, autoConnect: Boolean): Intent {
            return Intent(context, TerminalActivity::class.java).putExtra(EXTRA_AUTO_CONNECT, autoConnect)
        }
    }
}
