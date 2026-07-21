package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for OSC filtering (#126) and carriage-return overwrite (#127).
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewEscapeTest {

    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        view = TerminalView(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    // --- OSC sequences (#126) ---

    @Test
    fun oscTitleSequenceBelTerminatedIsFiltered() {
        view.appendLog("\u001B]0;larry@edge: ~\u0007hello\n")
        assertEquals("hello\n", view.getRawText())
    }

    @Test
    fun oscSequenceStTerminatedIsFiltered() {
        view.appendLog("\u001B]2;title\u001B\\ok\n")
        assertEquals("ok\n", view.getRawText())
    }

    @Test
    fun oscSplitAcrossChunksIsFiltered() {
        view.appendLog("\u001B]0;lar")
        view.appendLog("ry@edge\u0007hello\n")
        assertEquals("hello\n", view.getRawText())
    }

    @Test
    fun escSplitFromBracketAcrossChunksIsFiltered() {
        view.appendLog("\u001B")
        view.appendLog("]0;title\u0007hello\n")
        assertEquals("hello\n", view.getRawText())
    }

    @Test
    fun unterminatedOscDoesNotSwallowForever() {
        view.appendLog("\u001B]0;" + "x".repeat(3000))
        view.appendLog("visible\n")
        assertEquals("visible\n", view.getRawText().takeLast(8))
    }

    @Test
    fun csiColorSequencesStillRender() {
        view.appendLog("\u001B[31mred\u001B[0m\n")
        assertEquals("red\n", view.text.toString())
    }

    // --- Carriage-return overwrite (#127) ---

    @Test
    fun carriageReturnRestartsCurrentLine() {
        view.appendLog("AAAA\rBB\n")
        assertEquals("BB\n", view.getRawText())
    }

    @Test
    fun promptRedrawAfterSigwinchDoesNotDuplicate() {
        view.appendLog("larry@edge:~ $ ")
        // bash redraws the prompt on SIGWINCH: CR + erase-line + prompt
        view.appendLog("\r\u001B[Klarry@edge:~ $ ")
        assertEquals("larry@edge:~ $ ", view.text.toString())
    }

    @Test
    fun progressBarRepaintsCollapseToLastState() {
        view.appendLog("10%\r20%\r30%\rdone\n")
        assertEquals("done\n", view.getRawText())
    }

    @Test
    fun carriageReturnSplitAcrossChunks() {
        view.appendLog("AAAA\r")
        view.appendLog("BB\n")
        assertEquals("BB\n", view.getRawText())
    }

    @Test
    fun crlfIsStillASingleNewline() {
        view.appendLog("line1\r\nline2\n")
        assertEquals("line1\nline2\n", view.getRawText())
    }

    @Test
    fun carriageReturnOnFirstLineWithoutNewline() {
        view.appendLog("abc\rxy\n")
        assertEquals("xy\n", view.getRawText())
    }

    // --- Backspace echo ---

    @Test
    fun backspaceEchoErasesCharacter() {
        view.appendLog("li")
        view.appendLog("\b \b") // remote echo of one backspace keypress
        view.appendLog("s\n")
        assertEquals("ls\n", view.getRawText())
    }

    @Test
    fun backspaceDoesNotCrossLineBoundary() {
        view.appendLog("line1\n")
        view.appendLog("\b\b\bx\n")
        assertEquals("line1\nx\n", view.getRawText())
    }

    @Test
    fun backspaceDoesNotCorruptAnsiEscapeSequences() {
        view.appendLog("\u001B[31mred\u001B[0m")
        view.appendLog("\b \b") // erase the last printable char, not the escape terminator
        assertEquals("\u001B[31mre\u001B[0m", view.getRawText())
    }
}
