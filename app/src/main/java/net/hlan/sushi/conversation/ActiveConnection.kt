package net.hlan.sushi.conversation

import net.hlan.sushi.TerminalBackend

/**
 * The SSH session the conversation attaches to, as [ConversationViewModel] sees it.
 * [TerminalSessionHolderConnection] is the production implementation; tests drive a fake.
 */
interface ActiveConnection {

    fun isConnected(): Boolean

    fun activeBackend(): TerminalBackend?

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    /** Callbacks may arrive on any thread. */
    interface Listener {
        fun onConnected()
        fun onDisconnected()
    }
}
