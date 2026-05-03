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
}
