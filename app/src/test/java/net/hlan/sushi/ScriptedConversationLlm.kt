package net.hlan.sushi

/** [ConversationLlm] that hands out queued replies in order, then repeats its last one. */
class ScriptedConversationLlm(private val replies: MutableList<String>) : ConversationLlm {

    val prompts = mutableListOf<String>()
    private var last: String = ""

    override suspend fun respond(
        userMessage: String,
        promptContext: String,
        history: List<ConversationTurn>
    ): GeminiResult {
        prompts.add(userMessage)
        val reply = if (replies.isEmpty()) last else replies.removeAt(0).also { last = it }
        return GeminiResult(true, reply)
    }
}
