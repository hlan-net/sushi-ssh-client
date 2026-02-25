package net.hlan.sushi

import com.jcraft.jsch.JSch
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.InputStreamReader
import java.io.OutputStream

data class SshConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
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

    fun connect(): SshConnectResult {
        return runCatching {
            val jsch = JSch()
            val newSession = jsch.getSession(config.username, config.host, config.port)
            newSession.setPassword(config.password)
            newSession.setConfig("StrictHostKeyChecking", "no")
            newSession.connect(CONNECTION_TIMEOUT_MS)
            session = newSession
            SshConnectResult(true, "Connected")
        }.getOrElse { error ->
            val message = error.message?.takeIf { it.isNotBlank() }
                ?: "Unable to connect. Check host and credentials."
            SshConnectResult(false, message)
        }
    }

    fun disconnect() {
        session?.disconnect()
        session = null
    }

    fun isConnected(): Boolean = session?.isConnected == true

    fun getConfig(): SshConnectionConfig = config

    fun runCommand(command: String, onLine: (String) -> Unit): SshCommandResult {
        val activeSession = session
        if (activeSession == null || !activeSession.isConnected) {
            return SshCommandResult(false, null, "Not connected")
        }

        return runCatching {
            val channel = activeSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val errorStream = LineOutputStream { line ->
                if (line.isNotBlank()) {
                    onLine("[stderr] $line")
                }
            }
            channel.setErrStream(errorStream)
            channel.inputStream = null
            val inputStream = channel.inputStream
            channel.connect(COMMAND_TIMEOUT_MS)

            InputStreamReader(inputStream).buffered().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        onLine(line)
                    }
                    line = reader.readLine()
                }
            }

            while (!channel.isClosed) {
                Thread.sleep(25)
            }

            val exitStatus = channel.exitStatus
            channel.disconnect()
            errorStream.close()
            SshCommandResult(true, exitStatus, "Command finished")
        }.getOrElse { error ->
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Command failed"
            SshCommandResult(false, null, message)
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 10000
        private const val COMMAND_TIMEOUT_MS = 10000
    }

    private class LineOutputStream(
        private val onLine: (String) -> Unit
    ) : OutputStream() {
        private val buffer = StringBuilder()

        override fun write(b: Int) {
            when (b.toChar()) {
                '\n' -> flushLine()
                '\r' -> Unit
                else -> buffer.append(b.toChar())
            }
        }

        override fun flush() {
            flushLine()
        }

        override fun close() {
            flushLine()
        }

        private fun flushLine() {
            if (buffer.isNotEmpty()) {
                onLine(buffer.toString())
                buffer.setLength(0)
            }
        }
    }
}
