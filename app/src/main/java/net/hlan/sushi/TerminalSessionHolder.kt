package net.hlan.sushi

/**
 * Singleton holder for the active terminal session.
 * Keeps a typed SshClient reference alongside the TerminalBackend so that
 * SSH-only callers (ConversationManager, SettingsActivity) continue to work
 * without being forced onto the interface.
 */
object TerminalSessionHolder {
    private var activeBackend: TerminalBackend? = null
    private var activeSshClient: SshClient? = null
    private var activeHostConfig: SshConnectionConfig? = null
    private var connectionListeners = mutableListOf<ConnectionListener>()

    fun setActiveConnection(backend: TerminalBackend, config: SshConnectionConfig) {
        activeBackend = backend
        activeSshClient = backend as? SshClient
        activeHostConfig = config
        notifyConnected()
    }

    fun clearActiveConnection() {
        activeBackend = null
        activeSshClient = null
        activeHostConfig = null
        notifyDisconnected()
    }

    fun getActiveBackend(): TerminalBackend? = activeBackend

    /** Non-null only when the active session is SSH. */
    fun getActiveSshClient(): SshClient? = activeSshClient

    fun getActiveConfig(): SshConnectionConfig? = activeHostConfig

    fun isConnected(): Boolean = activeBackend?.isConnected() == true

    fun addListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    private fun notifyConnected() {
        connectionListeners.forEach { it.onConnected() }
    }

    private fun notifyDisconnected() {
        connectionListeners.forEach { it.onDisconnected() }
    }

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
    }
}
