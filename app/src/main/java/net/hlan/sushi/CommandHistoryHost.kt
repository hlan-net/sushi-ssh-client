package net.hlan.sushi

/**
 * A host that appears in command history, for the browser's host filter.
 *
 * [hostLabel] is the label stored with that host's newest entry, so a renamed host shows its
 * current name.
 */
data class CommandHistoryHost(
    val hostId: String,
    val hostLabel: String
)
