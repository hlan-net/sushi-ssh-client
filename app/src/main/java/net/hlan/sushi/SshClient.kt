package net.hlan.sushi

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

enum class SshAuthPreference(val value: String) {
    AUTO("auto"),
    PASSWORD("password"),
    KEY("key");

    companion object {
        fun from(value: String?): SshAuthPreference {
            return entries.firstOrNull { it.value == value } ?: AUTO
        }
    }
}

data class SshConnectionConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val alias: String = "",
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val authPreference: String = SshAuthPreference.AUTO.value,
    val privateKey: String? = null,
    val jumpEnabled: Boolean = false,
    val jumpHostId: String? = null,
    val jumpHost: String = "",
    val jumpPort: Int = 22,
    val jumpUsername: String = "",
    val jumpPassword: String = ""
) {
    fun hasJumpServer(): Boolean = jumpEnabled && (
        !jumpHostId.isNullOrBlank() || (jumpHost.isNotBlank() && jumpUsername.isNotBlank())
    )

    fun resolvedAuthPreference(): SshAuthPreference = SshAuthPreference.from(authPreference)

    fun displayTarget(): String = if (alias.isNotBlank()) {
        "$alias ($username@$host:$port)"
    } else {
        "$username@$host:$port"
    }
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
    private var jumpSession: Session? = null
    private var jumpForwardPort: Int? = null
    private var shellChannel: ChannelShell? = null
    private var shellInput: OutputStream? = null
    private var shellReaderThread: Thread? = null

    fun connect(onLine: (String) -> Unit, streamMode: Boolean = false): SshConnectResult {
        var newJumpSession: Session? = null
        var newJumpForwardPort: Int? = null
        var newSession: Session? = null
        var newChannel: ChannelShell? = null
        return runCatching {
            val jsch = JSch()
            val authPreference = config.resolvedAuthPreference()
            val hasPrivateKey = !config.privateKey.isNullOrBlank()
            val shouldUseKey = when (authPreference) {
                SshAuthPreference.AUTO -> hasPrivateKey
                SshAuthPreference.PASSWORD -> false
                SshAuthPreference.KEY -> hasPrivateKey
            }
            val shouldUsePassword = when (authPreference) {
                SshAuthPreference.AUTO -> true
                SshAuthPreference.PASSWORD -> true
                SshAuthPreference.KEY -> false
            }

            if (authPreference == SshAuthPreference.KEY && !hasPrivateKey) {
                throw IllegalStateException("SSH key preferred but no private key is configured.")
            }

            if (shouldUseKey) {
                // Only use a passphrase if the key is actually encrypted
                val keyPassphrase: ByteArray? = null // Should be handled separately from the SSH password
                val privateKeyBytes = config.privateKey.orEmpty().toByteArray()
                jsch.addIdentity("key", privateKeyBytes, null, keyPassphrase)
            }
            val targetHost: String
            val targetPort: Int

            if (config.hasJumpServer()) {
                val createdJumpSession = jsch.getSession(
                    config.jumpUsername,
                    config.jumpHost,
                    config.jumpPort
                )
                newJumpSession = createdJumpSession
                if (shouldUsePassword && config.jumpPassword.isNotBlank()) {
                    createdJumpSession.setPassword(config.jumpPassword)
                }
                createdJumpSession.setConfig("StrictHostKeyChecking", "no")
                createdJumpSession.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
                createdJumpSession.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
                createdJumpSession.connect(CONNECTION_TIMEOUT_MS)
                val forwardedPort = createdJumpSession.setPortForwardingL(0, config.host, config.port)
                newJumpForwardPort = forwardedPort
                targetHost = "127.0.0.1"
                targetPort = forwardedPort
            } else {
                targetHost = config.host
                targetPort = config.port
            }

            val createdSession = jsch.getSession(config.username, targetHost, targetPort)
            newSession = createdSession
            if (shouldUsePassword && config.password.isNotBlank()) {
                createdSession.setPassword(config.password)
            }
            createdSession.setConfig("StrictHostKeyChecking", "no")
            createdSession.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
            createdSession.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
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

            jumpSession = newJumpSession
            jumpForwardPort = newJumpForwardPort
            session = createdSession
            shellChannel = createdChannel
            shellInput = outputStream
            startShellReader(inputStream, onLine, streamMode)
            SshConnectResult(true, "Connected")
        }.getOrElse { error ->
            newChannel?.disconnect()
            newSession?.disconnect()
            newJumpSession?.disconnect()
            jumpForwardPort = null
            jumpSession = null
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
        val activeJumpSession = jumpSession
        val forwardedPort = jumpForwardPort
        if (activeJumpSession != null && forwardedPort != null) {
            runCatching { activeJumpSession.delPortForwardingL(forwardedPort) }
        }
        activeJumpSession?.disconnect()
        jumpSession = null
        jumpForwardPort = null
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

    fun sendText(text: String): SshCommandResult {
        val activeChannel = shellChannel
        val output = shellInput
        if (activeChannel == null || !activeChannel.isConnected || output == null) {
            return SshCommandResult(false, null, "Not connected")
        }

        if (text.isEmpty()) {
            return SshCommandResult(true, null, "No input")
        }

        return runCatching {
            output.write(text.toByteArray())
            output.flush()
            SshCommandResult(true, null, "Input sent")
        }.getOrElse { error ->
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Input failed"
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
        private const val SERVER_ALIVE_INTERVAL_MS = 15000
        private const val SERVER_ALIVE_COUNT_MAX = 3
        private const val CTRL_C_ETX = 3
        private const val CTRL_D_EOT = 4
    }

    private fun startShellReader(
        inputStream: InputStream,
        onLine: (String) -> Unit,
        streamMode: Boolean
    ) {
        shellReaderThread = Thread {
            if (streamMode) {
                readShellOutputStream(inputStream, onLine)
            } else {
                readShellOutput(inputStream, onLine)
            }
        }.apply {
            isDaemon = true
            name = "SshShellReader"
            start()
        }
    }

    private fun readShellOutputStream(inputStream: InputStream, onData: (String) -> Unit) {
        val reader = InputStreamReader(inputStream)
        val buffer = CharArray(1024)

        try {
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) {
                    break
                }
                onData(String(buffer, 0, read))
            }
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun readShellOutput(inputStream: InputStream, onLine: (String) -> Unit) {
        val reader = InputStreamReader(inputStream)
        val buffer = CharArray(1024)
        val lineBuffer = StringBuilder()
        var lastWasCarriageReturn = false

        fun flushLine(includeNewline: Boolean) {
            if (lineBuffer.isNotEmpty()) {
                val payload = if (includeNewline) {
                    lineBuffer.toString() + "\n"
                } else {
                    lineBuffer.toString()
                }
                onLine(payload)
                lineBuffer.setLength(0)
            } else if (includeNewline) {
                onLine("\n")
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
                        '\r' -> {
                            flushLine(includeNewline = true)
                            lastWasCarriageReturn = true
                        }
                        '\n' -> {
                            if (lastWasCarriageReturn) {
                                lastWasCarriageReturn = false
                            } else {
                                flushLine(includeNewline = true)
                            }
                        }
                        else -> {
                            lastWasCarriageReturn = false
                            lineBuffer.append(ch)
                        }
                    }
                }

                val shouldFlush = runCatching { inputStream.available() == 0 }.getOrDefault(false)
                if (shouldFlush) {
                    flushLine(includeNewline = false)
                }
            }
        } finally {
            flushLine(includeNewline = false)
            runCatching { reader.close() }
        }
    }
}
