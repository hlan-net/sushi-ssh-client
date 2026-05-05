package net.hlan.sushi

/**
 * Persistent record of a single Gemini conversation turn, stored in SQLite.
 *
 * Distinct from [GeminiTranscriptEntry], which is the lightweight in-memory shape used
 * by the active dialog. Records are written by [ConversationManager] on every turn so
 * that history is browsable across sessions (USER_STORIES G-6).
 */
data class GeminiTranscriptRecord(
    val id: Long = 0L,
    val sessionId: String,
    val hostId: String?,
    val hostLabel: String?,
    val timestamp: Long,
    val userMessage: String,
    val geminiReply: String,
    val commandExecuted: String?,
    val commandOutput: String?,
    val success: Boolean
)

/**
 * Summary row for the history list — one entry per Gemini session.
 */
data class GeminiTranscriptSessionSummary(
    val sessionId: String,
    val hostLabel: String?,
    val firstMessage: String,
    val turnCount: Int,
    val startedAt: Long,
    val lastActivityAt: Long
)
