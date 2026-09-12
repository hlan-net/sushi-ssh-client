package net.hlan.sushi

/**
 * Parsing for the `EXECUTE: <command>` directive the model emits when it wants a command run.
 *
 * Split out of [ConversationManager] because multi-step troubleshooting (roadmap v0.8.0) parses
 * the directive twice per step — once in the initial reply, once in the interpretation of the
 * previous result — and the rules for stripping it out of user-visible text need to be identical
 * in both places.
 */
object ExecuteDirective {

    /** Captures the command on a directive line. */
    private val DIRECTIVE = Regex("^[ \\t]*EXECUTE:[ \\t]*(.+?)[ \\t]*$", RegexOption.MULTILINE)

    /** Matches the whole directive line, trailing newline included, for removal. */
    private val DIRECTIVE_LINE = Regex("^[ \\t]*EXECUTE:.*(?:\\r?\\n)?", RegexOption.MULTILINE)

    /**
     * A directive whose command the model put on the next line instead of after the marker.
     * Both [parse] and [strip] work line by line, so such a directive would otherwise parse as
     * empty while [strip] still removed the marker — the command would vanish silently into the
     * text shown to the user. Joining the two lines first keeps the two in agreement.
     */
    private val SPLIT_DIRECTIVE = Regex(
        "^([ \\t]*EXECUTE:)[ \\t]*\\r?\\n[ \\t]*(?=\\S)",
        RegexOption.MULTILINE
    )

    /** [response] with a command on the line below its `EXECUTE:` marker pulled up onto it. */
    private fun joinSplitDirectives(response: String): String =
        SPLIT_DIRECTIVE.replace(response, "$1 ")

    /**
     * First command requested in [response], or null when it contains no directive.
     *
     * Surrounding backticks are stripped: models frequently wrap the command in inline code
     * even when asked not to, and the backticks would otherwise reach the shell.
     */
    fun parse(response: String): String? {
        val raw = DIRECTIVE.find(joinSplitDirectives(response))
            ?.groupValues?.get(1)?.trim()
            ?: return null
        val unwrapped = raw.trim('`').trim()
        return unwrapped.ifBlank { null }
    }

    /**
     * [response] with every directive line removed, so the text can be shown to the user.
     * Runs of blank lines left behind by the removal are collapsed.
     */
    fun strip(response: String): String {
        return DIRECTIVE_LINE.replace(joinSplitDirectives(response), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
