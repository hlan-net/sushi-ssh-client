package net.hlan.sushi

interface TerminalBackend {
    fun connect(
        onLine: (String) -> Unit,
        streamMode: Boolean = false,
        onConnectionClosed: (() -> Unit)? = null,
    ): SshConnectResult
    fun isConnected(): Boolean
    fun sendText(text: String): SshCommandResult
    fun sendCommand(command: String): SshCommandResult
    fun sendCtrlC()
    fun sendCtrlD()
    fun resizePty(col: Int, row: Int, widthPx: Int, heightPx: Int)
    fun disconnect()

    /**
     * Execute a command and capture its output without writing to the interactive PTY stream.
     * Used by [ConversationManager] and [PlayRunner] to run commands and read back their results.
     *
     * SSH implementations open a separate exec channel; local implementations spawn a subprocess.
     *
     * @param command Shell command to run.
     * @param timeoutMs Maximum time to wait before aborting.
     * @return [SshCommandResult] with success=true when exit code is 0.
     */
    fun execCommand(command: String, timeoutMs: Long = 30_000L): SshCommandResult
}
