package net.hlan.sushi

import java.util.regex.Pattern

/**
 * The terminal's line buffer: escape-sequence filtering, carriage-return overwrite, backspace
 * erase and line/char trimming. Pure Kotlin — no Android dependency — so it is JVM-testable;
 * [TerminalView] renders it, additionally turning SGR codes into colour spans.
 */
class TerminalBuffer {

    private val raw = StringBuilder()
    private var pendingCarriageReturn = false
    private var escState = EscState.NONE
    private var escStringLength = 0

    /**
     * Escape sequences can be split across network chunks, so the filter state must persist
     * between [append] calls. [IN_STRING] covers the string sequences (OSC, DCS, SOS, PM, APC)
     * that run until BEL or ST; [ESC_INTERMEDIATE] covers the two-part escapes whose first byte
     * is an intermediate, such as the charset designator `ESC ( B`.
     */
    private enum class EscState { NONE, ESC_SEEN, IN_STRING, IN_STRING_ESC_SEEN, ESC_INTERMEDIATE }

    companion object {
        private const val MAX_LINES = 500
        private const val MAX_CHARS = 200_000
        // Unterminated string-sequence guard: a missing BEL/ST must not swallow output forever.
        private const val MAX_ESC_STRING_LENGTH = 2048
        // Prevent DoS from extremely large input strings by truncating.
        private const val MAX_APPEND_LENGTH = 50_000

        /**
         * Bytes that turn an escape into a string sequence running until BEL or ST: OSC (`]`,
         * xterm window titles), DCS (`P`), SOS (`X`), PM (`^`) and APC (`_`).
         */
        private const val STRING_SEQUENCE_STARTERS = "]PX^_"

        internal val ESCAPE_PATTERN: Pattern = Pattern.compile("\u001B\\[[0-9;?]*[a-ln-zA-LN-Z]")
        internal val SGR_PATTERN: Pattern = Pattern.compile("\u001B\\[([0-9;]*)m")
    }

    /** The buffered text: escape sequences filtered per [append]'s rules, CSI (colour) passed through. */
    val text: String get() = raw.toString()

    /** [text] with every recognised escape sequence, SGR colour codes included, stripped for display. */
    fun render(): String {
        val stripped = ESCAPE_PATTERN.matcher(raw).replaceAll("")
        return SGR_PATTERN.matcher(stripped).replaceAll("")
    }

    /**
     * Appends [chunk], applying escape filtering, CR overwrite and backspace erase, then trims to
     * [MAX_LINES]/[MAX_CHARS]. Returns the raw prefix trimming dropped, if any, so a caller
     * tracking a rendered offset (e.g. a text selection) can account for it.
     */
    fun append(chunk: String): String {
        val safeText = if (chunk.length > MAX_APPEND_LENGTH) chunk.substring(chunk.length - MAX_APPEND_LENGTH) else chunk
        for (ch in safeText) processChar(ch)
        return trim()
    }

    /**
     * Appends [line] as the app's own status line, as opposed to [append]'s stream of remote
     * output: put on a line of its own, adding neither a leading blank line when the buffer
     * already sits at the start of one nor a trailing one when [line] ends in a newline itself.
     */
    fun appendLine(line: String): String {
        var dropped = ""
        if (raw.isNotEmpty() && !raw.endsWith("\n")) {
            dropped += append("\n")
        }
        dropped += append(if (line.endsWith("\n")) line else "$line\n")
        return dropped
    }

    fun clear() {
        raw.setLength(0)
        pendingCarriageReturn = false
        escState = EscState.NONE
    }

    private fun trim(): String {
        var cutIndex = 0
        if (raw.length > MAX_CHARS) {
            cutIndex = raw.length - MAX_CHARS
        }

        var newlineCount = 0
        for (i in 0 until raw.length) {
            if (raw[i] == '\n') {
                newlineCount++
            }
        }

        if (newlineCount > MAX_LINES) {
            var linesToDrop = newlineCount - MAX_LINES
            var i = 0
            while (linesToDrop > 0 && i < raw.length) {
                if (raw[i] == '\n') {
                    linesToDrop--
                }
                i++
            }
            cutIndex = maxOf(cutIndex, i)
        }

        if (cutIndex > 0) {
            val dropped = raw.substring(0, cutIndex)
            raw.delete(0, cutIndex)
            return dropped
        }
        return ""
    }

    /**
     * Filters escape sequences out of the stream before buffering, with one exception: CSI
     * (`ESC [ ...`) passes through, because the view's colour renderer parses it and strips the
     * rest.
     *
     * Everything else is consumed here and never reaches the buffer — the string sequences
     * (OSC, DCS, SOS, PM, APC), the two-byte escapes a shell emits around its prompt
     * (`ESC =` / `ESC >` keypad mode, `ESC 7` / `ESC 8` cursor save), and the charset
     * designators (`ESC ( B`). Emitting the withheld ESC for those used to leak their payload
     * as text: `ESC = ESC ( B` printed a literal `=(B` at the prompt.
     *
     * An `ESC` always re-synchronises, in every state: a sequence the remote truncated cannot
     * swallow the output that follows it.
     */
    private fun processChar(ch: Char) {
        when (escState) {
            EscState.ESC_SEEN -> {
                when {
                    // A second ESC abandons this sequence and starts a new one.
                    ch == '\u001B' -> return
                    ch == '[' -> {
                        // CSI: hand both bytes to the buffer for the view's colour renderer to deal with.
                        escState = EscState.NONE
                        appendChar('\u001B')
                    }
                    ch in STRING_SEQUENCE_STARTERS -> {
                        escState = EscState.IN_STRING
                        escStringLength = 0
                        return
                    }
                    ch.isEscapeIntermediate() -> {
                        escState = EscState.ESC_INTERMEDIATE
                        return
                    }
                    // A control character is executed where it stands; the escape is abandoned.
                    ch < ' ' -> escState = EscState.NONE
                    // Any other byte is the final one of a two-byte escape: consume both.
                    else -> {
                        escState = EscState.NONE
                        return
                    }
                }
            }
            EscState.ESC_INTERMEDIATE -> {
                when {
                    ch == '\u001B' -> escState = EscState.ESC_SEEN
                    ch.isEscapeIntermediate() -> Unit
                    ch < ' ' -> {
                        escState = EscState.NONE
                        appendChar(ch)
                    }
                    // The final byte, e.g. the `B` of `ESC ( B`.
                    else -> escState = EscState.NONE
                }
                return
            }
            EscState.IN_STRING -> {
                escStringLength++
                when {
                    ch == '\u0007' -> escState = EscState.NONE
                    ch == '\u001B' -> escState = EscState.IN_STRING_ESC_SEEN
                    escStringLength > MAX_ESC_STRING_LENGTH -> escState = EscState.NONE
                }
                return
            }
            EscState.IN_STRING_ESC_SEEN -> {
                if (ch == '\\') {
                    // ST: the string sequence ends here.
                    escState = EscState.NONE
                    return
                }
                // Any other byte after that ESC abandons the string and begins a new escape,
                // as the VT500 parser has it — ESC leaves the string state whatever follows it.
                // Returning to the string instead let an unterminated OSC swallow the next
                // sequence and everything after it, up to the length guard. This recurses
                // exactly once: ESC_SEEN never re-enters.
                escState = EscState.ESC_SEEN
                processChar(ch)
                return
            }
            EscState.NONE -> Unit
        }
        if (ch == '\u001B') {
            escState = EscState.ESC_SEEN
            return
        }
        appendChar(ch)
    }

    /**
     * Intermediate bytes (0x20-0x2F) — `ESC ( B`, `ESC # 8`, `ESC % G` — each announce that one
     * more byte belongs to the sequence.
     */
    private fun Char.isEscapeIntermediate(): Boolean = this in ' '..'/'

    /**
     * Appends with carriage-return overwrite semantics: a `\r` not followed by `\n`
     * restarts the current line, so shell prompt redraws (SIGWINCH) and progress bars
     * (wget, apt) repaint one line instead of appending duplicates.
     */
    private fun appendChar(ch: Char) {
        when (ch) {
            '\r' -> pendingCarriageReturn = true
            '\n' -> {
                raw.append('\n')
                pendingCarriageReturn = false
            }
            '\b' -> {
                // Remote echoes "\b \b" to erase a character; apply the erase locally.
                eraseLastPrintableChar()
            }
            else -> {
                if (pendingCarriageReturn) {
                    val lastNewline = raw.lastIndexOf("\n")
                    raw.setLength(if (lastNewline >= 0) lastNewline + 1 else 0)
                    pendingCarriageReturn = false
                }
                raw.append(ch)
            }
        }
    }

    /**
     * Erases the last printable character on the current line, never crossing a `\n`.
     * Trailing CSI/SGR escape sequences (`ESC [ [0-9;?]* letter`, e.g. a `ESC[0m`
     * color reset) are skipped rather than truncated, so a backspace can't corrupt a
     * still-open escape sequence into raw codes. Every other escape is already stripped
     * upstream in [processChar], so only CSI sequences and printable text reach the buffer.
     */
    private fun eraseLastPrintableChar() {
        var i = raw.length - 1
        while (i >= 0) {
            val c = raw[i]
            if (c == '\n') return
            if (c.isLetter()) {
                // Possible CSI/SGR terminator — walk back over its parameter bytes.
                var j = i - 1
                while (j >= 0 && (raw[j].isDigit() || raw[j] == ';' || raw[j] == '?')) {
                    j--
                }
                if (j >= 1 && raw[j] == '[' && raw[j - 1] == '\u001B') {
                    i = j - 2
                    continue
                }
            }
            raw.deleteCharAt(i)
            return
        }
    }
}
