package net.hlan.sushi.conversation

import net.hlan.sushi.ConversationResult

/**
 * A CONFIRM-tier command waiting for the user's yes or no.
 *
 * [turnId] is the transcript row the run continues in once confirmed. For an AI turn
 * ([kind] = [Kind.AI]) [result] carries the run so far — the narrative to resume from and the
 * steps already executed, which a decline persists so the work is not lost. A raw-mode command
 * ([Kind.RAW]) has nothing to resume: it either runs or it does not.
 */
data class PendingConfirmation(
    val command: String,
    val userMessage: String,
    val kind: Kind,
    val turnId: Long,
    val result: ConversationResult
) {
    enum class Kind { AI, RAW }
}
