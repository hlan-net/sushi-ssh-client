package net.hlan.sushi

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

data class SshConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val privateKey: String? = null
) {
    fun displayTarget(): String = "$username@$host:$port"
}

data class SshConnectResult(
    val success: Boolean,
    val message: String
)

data class SshCommandResult(
    val success: Boolean,
    val exitStatus: Int?,
    val message: String
)

class SshClient(private val config: SshConnectionConfig) {
    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellInput: OutputStream? = null
    private var shellReaderThread: Thread? = null

    fun connect(onLine: (String) -> Unit): SshConnectResult {
        var newSession: Session? = null
        var newChannel: ChannelShell? = null
        return runCatching {
            val jsch = JSch()
            if (!config.privateKey.isNullOrBlank()) {
                // Only use a passphrase if the key is actually encrypted
                val keyPassphrase: ByteArray? = null // Should be handled separately from the SSH password
                jsch.addIdentity("key", config.privateKey.toByteArray(), null, keyPassphrase)
            }
            val createdSession = jsch.getSession(config.username, config.host, config.port)
            newSession = createdSession
            if (config.privateKey.isNullOrBlank() && config.password.isNotBlank()) {
                createdSession.setPassword(config.password)
            }
            createdSession.setConfig("StrictHostKeyChecking", "no")
            createdSession.connect(CONNECTION_TIMEOUT_MS)

            val createdChannel = createdSession.openChannel("shell") as? ChannelShell
                ?: throw IllegalStateException("Unable to open shell channel")
            newChannel = createdChannel
            createdChannel.setPty(true)

            val inputStream = createdChannel.inputStream
                ?: throw IllegalStateException("Shell input stream unavailable")
            val outputStream = createdChannel.outputStream
                ?: throw IllegalStateException("Shell output stream unavailable")
            createdChannel.connect(SHELL_CONNECT_TIMEOUT_MS)

            session = createdSession
            shellChannel = createdChannel
            shellInput = outputStream
            startShellReader(inputStream, onLine)
            SshConnectResult(true, "Connected")
        }.getOrElse { error ->
            newChannel?.disconnect()
            newSession?.disconnect()
            session = null
            shellChannel = null
            shellInput = null
            shellReaderThread = null
            val message = error.message?.takeIf { it.isNotBlank() }
                ?: "Unable to connect. Check host and credentials."
            SshConnectResult(false, message)
        }
    }

    fun disconnect() {
        shellChannel?.disconnect()
        shellChannel = null
        runCatching { shellInput?.close() }
        shellInput = null
        session?.disconnect()
        session = null
        shellReaderThread = null
    }

    fun isConnected(): Boolean = session?.isConnected == true && shellChannel?.isConnected == true

    fun resizePty(col: Int, row: Int, wp: Int, hp: Int) {
        val activeChannel = shellChannel
        if (activeChannel != null && activeChannel.isConnected) {
            runCatching {
                activeChannel.setPtySize(col, row, wp, hp)
            }
        }
    }

    fun getConfig(): SshConnectionConfig = config

    fun sendCommand(command: String): SshCommandResult {
        val activeChannel = shellChannel
        val output = shellInput
        if (activeChannel == null || !activeChannel.isConnected || output == null) {
            return SshCommandResult(false, null, "Not connected")
        }

        return runCatching {
            val payload = if (command.endsWith("\n")) command else "$command\n"
            output.write(payload.toByteArray())
            output.flush()
            SshCommandResult(true, null, "Command sent")
        }.getOrElse { error ->
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Command failed"
            SshCommandResult(false, null, message)
        }
    }

    fun sendCtrlC() {
        runCatching {
            shellInput?.apply {
                write(CTRL_C_ETX) // ETX (End of Text)
                flush()
            }
        }
    }

    fun sendCtrlD() {
        runCatching {
            shellInput?.apply {
                write(CTRL_D_EOT) // EOT (End of Transmission)
                flush()
            }
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 10000
        private const val SHELL_CONNECT_TIMEOUT_MS = 10000
        private const val CTRL_C_ETX = 3
        private const val CTRL_D_EOT = 4
    }

    private fun startShellReader(inputStream: InputStream, onLine: (String) -> Unit) {
        shellReaderThread = Thread {
            readShellOutput(inputStream, onLine)
        }.apply {
            isDaemon = true
            name = "SshShellReader"
            start()
        }
    }

    private fun readShellOutput(inputStream: InputStream, onLine: (String) -> Unit) {
        val reader = InputStreamReader(inputStream)
        val buffer = CharArray(1024)
        val lineBuffer = StringBuilder()

        fun flushLine() {
            if (lineBuffer.isNotEmpty()) {
                onLine(lineBuffer.toString())
                lineBuffer.setLength(0)
            }
        }

        try {
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) {
                    break
                }

                for (i in 0 until read) {
                    when (val ch = buffer[i]) {
                        '\n', '\r' -> flushLine()
                        else -> lineBuffer.append(ch)
                    }
                }

                val shouldFlush = runCatching { inputStream.available() == 0 }.getOrDefault(false)
                if (shouldFlush) {
                    flushLine()
                }
            }
        } finally {
            flushLine()
            runCatching { reader.close() }
        }
    }
}
