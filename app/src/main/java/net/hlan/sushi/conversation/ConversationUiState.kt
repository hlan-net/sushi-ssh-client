package net.hlan.sushi.conversation

/**
 * Everything the conversation screen renders, as one immutable value.
 *
 * Owned by [ConversationViewModel]; the legacy dialog and, next, the Compose screen are pure
 * functions of it. Strings that need resources are left as data ([hostLabel], [status]) and
 * worded by the renderer.
 */
data class ConversationUiState(
    val transcript: List<TranscriptItem> = emptyList(),
    /** A request or command is running; input is disabled and a progress indicator shows. */
    val isBusy: Boolean = false,
    /** Raw Terminal Mode: input goes straight to the shell, bypassing the AI. */
    val isRawMode: Boolean = false,
    /** The AI may chain SAFE follow-up commands on its own. */
    val autoTroubleshoot: Boolean = true,
    /** Short label of the host the live conversation is bound to; null when not connected. */
    val hostLabel: String? = null,
    val status: ConversationStatus = ConversationStatus.Disconnected,
    /** A CONFIRM-tier command the user has not yet approved or declined. */
    val pendingConfirmation: PendingConfirmation? = null,
    /** The most recent reply or command output, what the Copy action puts on the clipboard. */
    val lastOutput: String = ""
) {
    /** Whether there is something worth copying. */
    val hasOutput: Boolean
        get() = !isBusy && lastOutput.isNotBlank()
}
