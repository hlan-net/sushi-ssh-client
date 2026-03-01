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
    val authPreference: String? = SshAuthPreference.AUTO.value,
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

    private data class AuthPlan(
        val shouldUseKey: Boolean,
        val shouldUsePassword: Boolean
    )

    private data class JumpSessionResult(
        val session: Session,
        val forwardedPort: Int
    )

    fun connect(onLine: (String) -> Unit, streamMode: Boolean = false): SshConnectResult {
        var newJumpSession: Session? = null
        var newJumpForwardPort: Int? = null
        var newSession: Session? = null
        var newChannel: ChannelShell? = null
        return runCatching {
            val jsch = JSch()
            val authPlan = resolveAuthPlan(config)
            addPrivateKeyIdentity(jsch, authPlan)

            val jumpResult = establishJumpSession(jsch, authPlan)
            newJumpSession = jumpResult?.session
            newJumpForwardPort = jumpResult?.forwardedPort

            val targetHost = jumpResult?.let { "127.0.0.1" } ?: config.host
            val targetPort = jumpResult?.forwardedPort ?: config.port

            val createdSession = establishTargetSession(jsch, targetHost, targetPort, authPlan)
            newSession = createdSession

            val createdChannel = openShellChannel(createdSession)
            newChannel = createdChannel

            val inputStream = createdChannel.inputStream
                ?: throw IllegalStateException("Shell input stream unavailable")
            val outputStream = createdChannel.outputStream
                ?: throw IllegalStateException("Shell output stream unavailable")

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

    private fun resolveAuthPlan(config: SshConnectionConfig): AuthPlan {
        val authPreference = config.resolvedAuthPreference()
        val hasPrivateKey = !config.privateKey.isNullOrBlank()
        check(!(authPreference == SshAuthPreference.KEY && !hasPrivateKey)) {
            "SSH key preferred but no private key is configured."
        }

        return AuthPlan(
            shouldUseKey = when (authPreference) {
                SshAuthPreference.AUTO -> hasPrivateKey
                SshAuthPreference.PASSWORD -> false
                SshAuthPreference.KEY -> true
            },
            shouldUsePassword = when (authPreference) {
                SshAuthPreference.AUTO -> true
                SshAuthPreference.PASSWORD -> true
                SshAuthPreference.KEY -> false
            }
        )
    }

    private fun addPrivateKeyIdentity(jsch: JSch, authPlan: AuthPlan) {
        if (!authPlan.shouldUseKey) {
            return
        }
        val keyPassphrase: ByteArray? = null
        val privateKeyBytes = config.privateKey.orEmpty().toByteArray()
        jsch.addIdentity("key", privateKeyBytes, null, keyPassphrase)
    }

    private fun establishJumpSession(jsch: JSch, authPlan: AuthPlan): JumpSessionResult? {
        if (!config.hasJumpServer()) {
            return null
        }

        val createdJumpSession = jsch.getSession(config.jumpUsername, config.jumpHost, config.jumpPort)
        if (authPlan.shouldUsePassword && config.jumpPassword.isNotBlank()) {
            createdJumpSession.setPassword(config.jumpPassword)
        }
        configureSession(createdJumpSession)
        createdJumpSession.connect(CONNECTION_TIMEOUT_MS)
        val forwardedPort = createdJumpSession.setPortForwardingL(0, config.host, config.port)
        return JumpSessionResult(createdJumpSession, forwardedPort)
    }

    private fun establishTargetSession(
        jsch: JSch,
        targetHost: String,
        targetPort: Int,
        authPlan: AuthPlan
    ): Session {
        val createdSession = jsch.getSession(config.username, targetHost, targetPort)
        if (authPlan.shouldUsePassword && config.password.isNotBlank()) {
            createdSession.setPassword(config.password)
        }
        configureSession(createdSession)
        createdSession.connect(CONNECTION_TIMEOUT_MS)
        return createdSession
    }

    private fun openShellChannel(createdSession: Session): ChannelShell {
        val channel = createdSession.openChannel("shell") as? ChannelShell
            ?: throw IllegalStateException("Unable to open shell channel")
        channel.setPty(true)
        channel.connect(SHELL_CONNECT_TIMEOUT_MS)
        return channel
    }

    private fun configureSession(session: Session) {
        session.setConfig("StrictHostKeyChecking", "no")
        session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
        session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
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
                    lastWasCarriageReturn = processShellChar(
                        ch = buffer[i],
                        lineBuffer = lineBuffer,
                        lastWasCarriageReturn = lastWasCarriageReturn,
                        flushLine = ::flushLine
                    )
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

    private fun processShellChar(
        ch: Char,
        lineBuffer: StringBuilder,
        lastWasCarriageReturn: Boolean,
        flushLine: (Boolean) -> Unit
    ): Boolean {
        return when (ch) {
            '\r' -> {
                flushLine(true)
                true
            }
            '\n' -> {
                if (!lastWasCarriageReturn) {
                    flushLine(true)
                }
                false
            }
            else -> {
                lineBuffer.append(ch)
                false
            }
        }
    }
}
