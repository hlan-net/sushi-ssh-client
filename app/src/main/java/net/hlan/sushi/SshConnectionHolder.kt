package net.hlan.sushi

/**
 * Singleton holder for the active SSH connection.
 * Allows MainActivity's conversation feature to access the SSH client
 * that was established in TerminalActivity.
 */
object SshConnectionHolder {
    private var activeClient: SshClient? = null
    private var activeHostConfig: SshConnectionConfig? = null
    private var connectionListeners = mutableListOf<ConnectionListener>()

    /**
     * Set the active SSH client (called by TerminalActivity on connect).
     */
    fun setActiveConnection(client: SshClient, config: SshConnectionConfig) {
        activeClient = client
        activeHostConfig = config
        notifyConnected()
    }

    /**
     * Clear the active connection (called by TerminalActivity on disconnect).
     */
    fun clearActiveConnection() {
        activeClient = null
        activeHostConfig = null
        notifyDisconnected()
    }

    /**
     * Get the active SSH client, or null if not connected.
     */
    fun getActiveClient(): SshClient? = activeClient

    /**
     * Get the active host configuration, or null if not connected.
     */
    fun getActiveConfig(): SshConnectionConfig? = activeHostConfig

    /**
     * Check if there is an active SSH connection.
     */
    fun isConnected(): Boolean = activeClient?.isConnected() == true

    /**
     * Add a connection state listener.
     */
    fun addListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    /**
     * Remove a connection state listener.
     */
    fun removeListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    private fun notifyConnected() {
        connectionListeners.forEach { it.onConnected() }
    }

    private fun notifyDisconnected() {
        connectionListeners.forEach { it.onDisconnected() }
    }

    /**
     * Listener for SSH connection state changes.
     */
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
    }
}
