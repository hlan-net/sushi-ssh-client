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
