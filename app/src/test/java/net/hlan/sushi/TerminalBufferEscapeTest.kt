package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for escape filtering (#126) and carriage-return overwrite (#127).
 *
 * Moved from the instrumented `TerminalViewEscapeTest` when the escape state machine, CR
 * overwrite and backspace erase were extracted into [TerminalBuffer] (`ROADMAP.md` v0.9.0):
 * [TerminalBuffer.render] stands in for the old `view.text.toString()` where a test cared about
 * what would be displayed rather than the raw buffer — it strips the same escape sequences
 * [TerminalView]'s colour renderer does, just without resolving actual colours.
 */
class TerminalBufferEscapeTest {

    private lateinit var buffer: TerminalBuffer

    @Before
    fun setUp() {
        buffer = TerminalBuffer()
    }

    // --- OSC sequences (#126) ---

    @Test
    fun oscTitleSequenceBelTerminatedIsFiltered() {
        buffer.append("\u001B]0;larry@edge: ~\u0007hello\n")
        assertEquals("hello\n", buffer.text)
    }

    @Test
    fun oscSequenceStTerminatedIsFiltered() {
        buffer.append("\u001B]2;title\u001B\\ok\n")
        assertEquals("ok\n", buffer.text)
    }

    @Test
    fun oscSplitAcrossChunksIsFiltered() {
        buffer.append("\u001B]0;lar")
        buffer.append("ry@edge\u0007hello\n")
        assertEquals("hello\n", buffer.text)
    }

    @Test
    fun escSplitFromBracketAcrossChunksIsFiltered() {
        buffer.append("\u001B")
        buffer.append("]0;title\u0007hello\n")
        assertEquals("hello\n", buffer.text)
    }

    @Test
    fun unterminatedOscDoesNotSwallowForever() {
        buffer.append("\u001B]0;" + "x".repeat(3000))
        buffer.append("visible\n")
        assertEquals("visible\n", buffer.text.takeLast(8))
    }

    @Test
    fun csiColorSequencesStillRender() {
        buffer.append("\u001B[31mred\u001B[0m\n")
        assertEquals("red\n", buffer.render())
    }

    // --- Non-CSI escapes ---

    @Test
    fun keypadAndCharsetEscapesLeaveNothingVisible() {
        // What bash emits around its prompt; these used to print a literal "=(B".
        buffer.append("\u001B=\u001B(Blarry@edge:~ $ ")
        assertEquals("larry@edge:~ $ ", buffer.text)
    }

    @Test
    fun twoByteEscapesAreFiltered() {
        buffer.append("\u001B>\u001B7a\u001B8\u001BMok\n")
        assertEquals("aok\n", buffer.text)
    }

    @Test
    fun charsetDesignatorSplitAcrossChunksIsFiltered() {
        buffer.append("\u001B(")
        buffer.append("Bhello\n")
        assertEquals("hello\n", buffer.text)
    }

    @Test
    fun dcsStringSequenceIsFiltered() {
        buffer.append("\u001BPquery\u001B\\ok\n")
        assertEquals("ok\n", buffer.text)
    }

    @Test
    fun escFollowedByEscStartsANewSequence() {
        buffer.append("\u001B\u001B=ok\n")
        assertEquals("ok\n", buffer.text)
    }

    @Test
    fun controlCharacterAbandonsAnUnfinishedEscape() {
        buffer.append("a\u001B\nb\n")
        assertEquals("a\nb\n", buffer.text)
    }

    @Test
    fun clearLogResetsTheFilterState() {
        buffer.append("\u001B")
        buffer.clear()
        buffer.append("(B\n")
        assertEquals("(B\n", buffer.text)
    }

    @Test
    fun escapeAfterATruncatedStringSequenceReSyncs() {
        // A remote that cuts an OSC short must not take the next sequence down with it.
        buffer.append("\u001B]0;title\u001B\u001B[31mvisible\n")
        assertEquals("visible\n", buffer.render())
    }

    @Test
    fun twoByteEscapeAbandonsATruncatedStringSequence() {
        buffer.append("\u001B]0;title\u001B=visible\n")
        assertEquals("visible\n", buffer.text)
    }

    // --- Carriage-return overwrite (#127) ---

    @Test
    fun carriageReturnRestartsCurrentLine() {
        buffer.append("AAAA\rBB\n")
        assertEquals("BB\n", buffer.text)
    }

    @Test
    fun promptRedrawAfterSigwinchDoesNotDuplicate() {
        buffer.append("larry@edge:~ $ ")
        // bash redraws the prompt on SIGWINCH: CR + erase-line + prompt
        buffer.append("\r\u001B[Klarry@edge:~ $ ")
        assertEquals("larry@edge:~ $ ", buffer.render())
    }

    @Test
    fun progressBarRepaintsCollapseToLastState() {
        buffer.append("10%\r20%\r30%\rdone\n")
        assertEquals("done\n", buffer.text)
    }

    @Test
    fun carriageReturnSplitAcrossChunks() {
        buffer.append("AAAA\r")
        buffer.append("BB\n")
        assertEquals("BB\n", buffer.text)
    }

    @Test
    fun crlfIsStillASingleNewline() {
        buffer.append("line1\r\nline2\n")
        assertEquals("line1\nline2\n", buffer.text)
    }

    @Test
    fun carriageReturnOnFirstLineWithoutNewline() {
        buffer.append("abc\rxy\n")
        assertEquals("xy\n", buffer.text)
    }

    // --- Backspace echo ---

    @Test
    fun backspaceEchoErasesCharacter() {
        buffer.append("li")
        buffer.append("\b \b") // remote echo of one backspace keypress
        buffer.append("s\n")
        assertEquals("ls\n", buffer.text)
    }

    @Test
    fun backspaceDoesNotCrossLineBoundary() {
        buffer.append("line1\n")
        buffer.append("\b\b\bx\n")
        assertEquals("line1\nx\n", buffer.text)
    }

    @Test
    fun backspaceDoesNotCorruptAnsiEscapeSequences() {
        buffer.append("\u001B[31mred\u001B[0m")
        buffer.append("\b \b") // erase the last printable char, not the escape terminator
        assertEquals("\u001B[31mre\u001B[0m", buffer.text)
    }
}
