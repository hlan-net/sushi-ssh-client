package net.hlan.sushi

/**
 * Represents a single turn in a conversation between the user and the target system.
 * Used to provide conversation history context to the LLM.
 */
data class ConversationTurn(
    val timestamp: Long,
    val userMessage: String,
    val systemResponse: String,
    val commandExecuted: String? = null,
    val commandOutput: String? = null,
    val executionSuccess: Boolean = true
)
