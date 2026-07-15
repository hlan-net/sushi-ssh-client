package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalViewEscapeSequenceTest {

    private val esc = "\u001B"
    private val bel = "\u0007"

    @Test
    fun oscWindowTitleSequenceIsStripped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminalView = TerminalView(context)

        terminalView.appendLog("$esc]0;some title$bel" + "hello\n")

        assertEquals("hello\n", terminalView.text.toString())
    }

    @Test
    fun bareCarriageReturnOverwritesCurrentLine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminalView = TerminalView(context)

        terminalView.appendLog("Loading...\rDone\n")

        assertEquals("Done\n", terminalView.text.toString())
    }

    @Test
    fun carriageReturnLineFeedIsARegularLineBreak() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminalView = TerminalView(context)

        terminalView.appendLog("abc\r\ndef")

        assertEquals("abc\ndef", terminalView.text.toString())
    }

    @Test
    fun pendingCarriageReturnAcrossChunksStillOverwrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminalView = TerminalView(context)

        terminalView.appendLog("Loading...\r")
        terminalView.appendLog("Done\n")

        assertEquals("Done\n", terminalView.text.toString())
    }
}
