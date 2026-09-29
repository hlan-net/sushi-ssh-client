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
    // Set from a Play's IO coroutine, read on the main thread by TerminalActivity.
    @Volatile
    private var expectedDisconnectHostId: String? = null

    fun setActiveConnection(backend: TerminalBackend, config: SshConnectionConfig) {
        activeBackend = backend
        activeSshClient = backend as? SshClient
        activeHostConfig = config
        notifyConnected()
    }

    fun clearActiveConnection() {
        // An expectation only ever belongs to the active session, so it ends with it — it must not
        // survive to misclassify a later, unrelated disconnect.
        expectedDisconnectHostId = null
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

    /**
     * Marks the next disconnect of the terminal session as expected — a Play that reboots the
     * host, for instance — so the terminal shows it as a routine drop rather than an error.
     *
     * Armed only when the active session is on [hostId]: a reboot of any other host has no
     * disconnect here to explain, and a pending mark for it would mislabel whatever disconnect
     * of that host came later. Returns whether it was armed.
     */
    fun expectDisconnect(hostId: String): Boolean {
        if (activeHostConfig?.id != hostId) {
            return false
        }
        expectedDisconnectHostId = hostId
        return true
    }

    /** Withdraws [expectDisconnect] for [hostId] — the command that would have caused it never ran. */
    fun cancelExpectedDisconnect(hostId: String) {
        if (expectedDisconnectHostId == hostId) {
            expectedDisconnectHostId = null
        }
    }

    /**
     * Consumes the expectation set by [expectDisconnect]: true only the first time it is checked
     * for the matching [hostId]. A mismatched or already-consumed call clears nothing further,
     * so a later, unrelated disconnect of the same host is not silently swallowed.
     */
    fun consumeExpectedDisconnect(hostId: String): Boolean {
        val expected = expectedDisconnectHostId == hostId
        if (expected) {
            expectedDisconnectHostId = null
        }
        return expected
    }

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
