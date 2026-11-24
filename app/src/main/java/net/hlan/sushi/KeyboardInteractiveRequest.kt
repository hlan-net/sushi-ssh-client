package net.hlan.sushi

/**
 * One `SSH_MSG_USERAUTH_INFO_REQUEST` round that the stored password cannot answer, as shown to
 * the user in the "Server sign-in" dialog (#191, Figma `node-id=131-2`).
 *
 * Every text field comes from the server and is shown as plain text: [from] strips control
 * characters, so a prompt cannot carry terminal escapes or line breaks into the dialog.
 */
data class KeyboardInteractiveRequest(
    /** `host:port` of the hop asking — the real endpoint, not the tunnelled 127.0.0.1 pair. */
    val hostLabel: String,
    /** True when the jump host asks rather than the target. */
    val isJumpHost: Boolean,
    /** The server's name for this round; often blank. */
    val name: String,
    /** The server's instruction text; often blank. */
    val instruction: String,
    /** One entry per prompt, in the server's order; one answer per prompt is expected back. */
    val prompts: List<Prompt>
) {
    data class Prompt(val text: String, val echo: Boolean)

    companion object {
        fun from(
            hostLabel: String,
            isJumpHost: Boolean,
            name: String?,
            instruction: String?,
            prompts: Array<String>,
            echo: BooleanArray
        ): KeyboardInteractiveRequest = KeyboardInteractiveRequest(
            hostLabel = hostLabel,
            isJumpHost = isJumpHost,
            name = plainText(name),
            instruction = plainText(instruction),
            // A missing echo flag counts as echo off: hiding an answer that could have been shown
            // costs nothing, showing one that should have been hidden leaks it.
            prompts = prompts.mapIndexed { i, text -> Prompt(plainText(text), echo.getOrElse(i) { false }) }
        )

        /** Control characters become spaces, runs of whitespace collapse, ends are trimmed. */
        internal fun plainText(raw: String?): String =
            raw.orEmpty()
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .replace(WHITESPACE_RUN, " ")
                .trim()

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
