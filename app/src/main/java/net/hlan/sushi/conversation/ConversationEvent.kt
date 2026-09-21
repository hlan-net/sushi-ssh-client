package net.hlan.sushi.conversation

/**
 * One-off things the screen shows once and does not keep: session-log lines, toasts, hints.
 * Delivered through [ConversationViewModel.events]; state that must survive rotation is in
 * [ConversationUiState] instead.
 */
sealed interface ConversationEvent {

    /** A line for the session log on the Plays tab. */
    data class LogLine(val text: String) : ConversationEvent

    /**
     * A command was generated without a live session (the pre-conversation path). The
     * renderer formats the log line, whose format is a non-translatable resource.
     */
    data class CommandGenerated(val prompt: String, val command: String) : ConversationEvent

    /** Something failed outside the normal result path; [message] is the exception's. */
    data class Error(val message: String?) : ConversationEvent

    /** The user tried to talk to a system while no session was initialised. */
    data object NotConnected : ConversationEvent

    /** The target had no persona file; the default one was used. */
    data object DefaultPersonaUsed : ConversationEvent
}
