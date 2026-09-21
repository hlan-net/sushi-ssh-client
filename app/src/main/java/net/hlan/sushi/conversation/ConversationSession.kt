package net.hlan.sushi.conversation

import net.hlan.sushi.ConversationManager

/**
 * A [ConversationManager] bound to the active SSH session, plus the host it talks to.
 * Built by [ConversationEnvironment.createSession]; the manager is not yet initialised.
 */
data class ConversationSession(
    val manager: ConversationManager,
    val hostId: String?,
    val hostLabel: String?
)
