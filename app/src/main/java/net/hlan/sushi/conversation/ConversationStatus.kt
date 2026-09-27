package net.hlan.sushi.conversation

/** Where the live conversation stands with the target system. */
sealed interface ConversationStatus {

    /** No SSH session, so nothing to talk to. */
    data object Disconnected : ConversationStatus

    /** A session exists; the persona is being read from the target. */
    data object Initializing : ConversationStatus

    /** The persona loaded; [identity] is what the target calls itself. */
    data class Connected(val identity: String) : ConversationStatus

    /** The persona could not be loaded; [message] is the reason. */
    data class Failed(val message: String) : ConversationStatus
}
