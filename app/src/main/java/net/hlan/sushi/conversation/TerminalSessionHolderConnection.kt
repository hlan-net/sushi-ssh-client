package net.hlan.sushi.conversation

import net.hlan.sushi.TerminalBackend
import net.hlan.sushi.TerminalSessionHolder

/** [ActiveConnection] over the process-wide [TerminalSessionHolder]. */
object TerminalSessionHolderConnection : ActiveConnection {

    private val adapters = mutableMapOf<ActiveConnection.Listener, TerminalSessionHolder.ConnectionListener>()

    override fun isConnected(): Boolean = TerminalSessionHolder.isConnected()

    override fun activeBackend(): TerminalBackend? = TerminalSessionHolder.getActiveBackend()

    override fun addListener(listener: ActiveConnection.Listener) {
        val adapter = object : TerminalSessionHolder.ConnectionListener {
            override fun onConnected() = listener.onConnected()
            override fun onDisconnected() = listener.onDisconnected()
        }
        synchronized(adapters) { adapters[listener] = adapter }
        TerminalSessionHolder.addListener(adapter)
    }

    override fun removeListener(listener: ActiveConnection.Listener) {
        val adapter = synchronized(adapters) { adapters.remove(listener) } ?: return
        TerminalSessionHolder.removeListener(adapter)
    }
}
