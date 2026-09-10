package net.hlan.sushi

/**
 * One executed command, persisted locally so history survives app restarts and can be
 * searched, re-run, and fed back into the AI context (USER_STORIES / roadmap v0.8.0).
 *
 * Only the first lines of output are kept — see [CommandHistoryDatabaseHelper.OUTPUT_SUMMARY_LINES] —
 * so the database does not grow without bound. Commands classified BLOCKED by [CommandSafety]
 * are never recorded.
 *
 * @param hostId Saved host id the command ran against; empty when the host is unknown.
 * @param hostLabel Human-readable host label captured at execution time; empty when unknown.
 */
data class CommandHistoryRecord(
    val id: Long = 0L,
    val hostId: String,
    val hostLabel: String,
    val command: String,
    val outputSummary: String,
    val exitStatus: Int?,
    val success: Boolean,
    val source: CommandSource,
    val timestamp: Long
)
