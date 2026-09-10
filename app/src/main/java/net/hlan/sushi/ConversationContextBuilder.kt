package net.hlan.sushi

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the extra context blocks appended to the target's `SUSHI.md` before a prompt is sent
 * to the model.
 *
 * Two blocks exist today (roadmap v0.8.0):
 * - **Infrastructure** — the other systems saved in the app, so the AI can answer questions
 *   about the wider setup without connecting to them (multi-system Option B).
 * - **Recent commands** — what was run on this host lately, so the AI can compare against a
 *   previous result or re-run "the same check as last time" instead of guessing.
 *
 * Kept free of Android dependencies so the prompt shape can be unit-tested directly.
 */
object ConversationContextBuilder {

    /** Marker the AI is told identifies the currently connected system. */
    private const val ACTIVE_MARKER = "this system"

    private val defaultTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * Join [persona] with any non-blank [sections], separated by horizontal rules so the model
     * sees them as distinct blocks. Blank sections are dropped, so a single-host user with no
     * history gets exactly the persona text they had before.
     */
    fun compose(persona: String, vararg sections: String): String {
        val extra = sections.filter { it.isNotBlank() }
        if (extra.isEmpty()) return persona
        return (listOf(persona.trimEnd()) + extra.map { it.trim() }).joinToString("\n\n---\n\n")
    }

    /**
     * Describe the user's saved hosts, marking the connected one.
     *
     * Returns an empty string when there is nothing worth saying — fewer than two hosts means
     * there is no "infrastructure" to be aware of.
     */
    fun infrastructureSection(
        hosts: List<SshConnectionConfig>,
        activeHostId: String?
    ): String {
        if (hosts.size < 2) return ""

        val lines = hosts.joinToString("\n") { host ->
            val target = when {
                host.kind == HostKind.LOCAL -> "Android device shell"
                host.username.isNotBlank() -> "${host.username}@${host.host}:${host.port}"
                else -> "${host.host}:${host.port}"
            }
            val name = host.alias.ifBlank { host.host.ifBlank { target } }
            val jump = jumpDescription(host, hosts)
            val active = if (host.id == activeHostId) " — $ACTIVE_MARKER" else ""
            "- $name ($target)$jump$active"
        }

        return """
## Infrastructure

Systems saved in the user's Sushi app:

$lines

Only the system marked "$ACTIVE_MARKER" is connected — you cannot run commands on the others.
Use this list to answer questions about the wider setup, and say which system you mean when
more than one could match.
        """.trimIndent()
    }

    private fun jumpDescription(host: SshConnectionConfig, hosts: List<SshConnectionConfig>): String {
        if (!host.hasJumpServer()) return ""
        val jumpName = host.jumpHostId
            ?.let { id -> hosts.firstOrNull { it.id == id } }
            ?.let { it.alias.ifBlank { it.host } }
            ?: host.jumpHost.ifBlank { return "" }
        return " — reached via $jumpName"
    }

    /**
     * Summarise the most recent commands run on the connected host, newest last so the list
     * reads chronologically.
     *
     * Returns an empty string when [records] is empty.
     */
    fun recentCommandsSection(
        records: List<CommandHistoryRecord>,
        hostLabel: String,
        formatTimestamp: (Long) -> String = ::formatDefault
    ): String {
        if (records.isEmpty()) return ""

        val target = hostLabel.ifBlank { "this system" }
        val lines = records
            .sortedBy { it.timestamp }
            .joinToString("\n") { record ->
                val outcome = record.outputSummary
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.take(120)
                    ?: if (record.success) "(no output)" else "(failed)"
                "- [${formatTimestamp(record.timestamp)}] ${record.command} → $outcome"
            }

        return """
## Recent commands on $target

$lines

These already ran. Refer to them when the user asks about a previous result or asks to repeat
a check; re-run a command only when a fresh result is actually needed.
        """.trimIndent()
    }

    private fun formatDefault(timestamp: Long): String =
        synchronized(defaultTimestampFormat) { defaultTimestampFormat.format(Date(timestamp)) }
}
