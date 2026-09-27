package net.hlan.sushi

/**
 * [TerminalBackend] that answers a fixed script and records what it was asked to run, for
 * JVM tests of everything above the shell. The persona and log-file setup that
 * [ConversationManager.initialize] performs is answered as infrastructure, not recorded.
 */
class FakeTerminalBackend(
    private val sushiMd: String = "# Test system\nYou are a test host.",
    private val results: MutableMap<String, SshCommandResult> = mutableMapOf()
) : TerminalBackend {

    val executed = mutableListOf<String>()

    /** Runs before a recorded command executes; a test can block here to hold the backend busy. */
    var beforeExecute: ((String) -> Unit)? = null

    /** Output streamed through `onChunk` before the result, per command. */
    private val chunks = mutableMapOf<String, List<String>>()

    /** When set, the SUSHI.md read returns this instead of [sushiMd] — for a failed init. */
    var sushiMdReadResult: SshCommandResult? = null

    fun on(command: String, result: SshCommandResult, streamed: List<String> = emptyList()) {
        results[command] = result
        if (streamed.isNotEmpty()) chunks[command] = streamed
    }

    override fun execCommand(
        command: String,
        timeoutMs: Long,
        onChunk: ((String) -> Unit)?
    ): SshCommandResult {
        if (command.startsWith("cat ~/.config/sushi/SUSHI.md")) {
            return sushiMdReadResult ?: SshCommandResult(true, 0, sushiMd)
        }
        if (command.startsWith("cat ~/.config/sushi/config.conf") ||
            command.startsWith("mkdir -p ") ||
            command.startsWith("printf ")
        ) {
            return SshCommandResult(true, 0, "")
        }
        executed.add(command)
        beforeExecute?.invoke(command)
        chunks[command]?.forEach { onChunk?.invoke(it) }
        return results[command] ?: SshCommandResult(true, 0, "ok: $command")
    }

    override fun connect(
        onLine: (String) -> Unit,
        streamMode: Boolean,
        onConnectionClosed: (() -> Unit)?
    ): SshConnectResult = SshConnectResult(true, "connected")

    override fun isConnected(): Boolean = true
    override fun sendText(text: String) = SshCommandResult(true, 0, "")
    override fun sendCommand(command: String) = SshCommandResult(true, 0, "")
    override fun sendCtrlC() = Unit
    override fun sendCtrlD() = Unit
    override fun resizePty(col: Int, row: Int, widthPx: Int, heightPx: Int) = Unit
    override fun disconnect() = Unit
}
