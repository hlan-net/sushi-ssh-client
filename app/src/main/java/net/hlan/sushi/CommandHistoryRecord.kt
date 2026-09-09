package net.hlan.sushi

/**
 * Where an executed command came from.
 *
 * Stored as a string column so future sources can be added without a schema migration;
 * unknown values read back as [UNKNOWN].
 */
enum class CommandSource {
    /** Issued by the AI via an `EXECUTE:` directive in the conversation. */
    CONVERSATION,

    /** Typed by the user in Raw Terminal Mode (no AI in the loop). */
    RAW,

    /** Rendered and run as part of a Play. */
    PLAY,

    /** Source column held a value this build does not know. */
    UNKNOWN;

    companion object {
        fun fromStorage(value: String?): CommandSource =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

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
