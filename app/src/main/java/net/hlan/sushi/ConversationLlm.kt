package net.hlan.sushi

/**
 * The single LLM call [ConversationManager] makes.
 *
 * Exists so the troubleshooting chain can be exercised without a network round trip: production
 * code passes the configured Gemini client (cloud or on-device), tests a scripted responder.
 */
fun interface ConversationLlm {

    /**
     * Answer [userMessage] with [promptContext] (persona + infrastructure + recent commands) and
     * the turns in [history] as background.
     */
    suspend fun respond(
        userMessage: String,
        promptContext: String,
        history: List<ConversationTurn>
    ): GeminiResult
}
