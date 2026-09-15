package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [TerminalView.appendLogLine] — the app's own status lines get a line each.
 *
 * Status strings carry no newline and [TerminalView.appendLog] only breaks a line when it sees a
 * real `\n`, so every status used to continue whatever was on screen, and the shell prompt that
 * arrived next continued it in turn:
 *
 * `[Terminal] Connecting...Connected to ekho (larry@192.168.1.11:22) · ssh.larry@ekho:~ $`
 *
 * The `ssh.` in the middle of that is the host kind from `displayTarget()` plus the full stop
 * ending "Connected to %1$s.", which together read as a hostname that does not exist.
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewLogLineTest {

    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        view = TerminalView(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    /** The reported line, end to end. */
    @Test
    fun connectSequence_putsEachStatusAndThePromptOnItsOwnLine() {
        view.appendLogLine("[Terminal] Connecting...")
        view.appendLogLine("Connected to ekho (larry@192.168.1.11:22) · ssh.")
        view.appendLog("larry@ekho:~ $ ")

        assertEquals(
            "[Terminal] Connecting...\n" +
                "Connected to ekho (larry@192.168.1.11:22) · ssh.\n" +
                "larry@ekho:~ $ ",
            view.getRawText()
        )
    }

    @Test
    fun consecutiveStatusLines_eachGetTheirOwnLine() {
        view.appendLogLine("[Terminal] Connecting...")
        view.appendLogLine("[Terminal] Connection failed: Auth cancel for methods 'publickey,password'")

        assertEquals(
            "[Terminal] Connecting...\n" +
                "[Terminal] Connection failed: Auth cancel for methods 'publickey,password'\n",
            view.getRawText()
        )
    }

    /** A status arriving mid-line — remote output still on the current row — starts a new one. */
    @Test
    fun statusLine_afterUnterminatedRemoteOutput_startsFresh() {
        view.appendLog("larry@ekho:~ $ ")
        view.appendLogLine("[Terminal] Connection lost unexpectedly.")

        assertEquals(
            "larry@ekho:~ $ \n[Terminal] Connection lost unexpectedly.\n",
            view.getRawText()
        )
    }

    /** Remote output that already ends a line must not gain a blank one. */
    @Test
    fun statusLine_afterTerminatedRemoteOutput_addsNoBlankLine() {
        view.appendLog("total 0\n")
        view.appendLogLine("[Terminal] Connection lost unexpectedly.")

        assertEquals("total 0\n[Terminal] Connection lost unexpectedly.\n", view.getRawText())
    }

    /** Nothing on screen yet: the first status must not be pushed down by a leading break. */
    @Test
    fun firstStatusLine_doesNotOpenWithABlankLine() {
        view.appendLogLine("[Terminal] Connecting...")

        assertEquals("[Terminal] Connecting...\n", view.getRawText())
    }

    /**
     * The remote stream keeps its own line discipline — [TerminalView.appendLog] is unchanged,
     * or progress bars and prompt redraws would each land on a line of their own.
     */
    @Test
    fun remoteOutput_isStillAppendedWithoutAddedBreaks() {
        view.appendLog("one")
        view.appendLog("two")

        assertEquals("onetwo", view.getRawText())
    }

    /** A status line carrying its own newline must not end up double-spaced. */
    @Test
    fun statusLineEndingInANewline_isNotTerminatedTwice() {
        view.appendLogLine("[Terminal] Connecting...\n")

        assertEquals("[Terminal] Connecting...\n", view.getRawText())
    }
}
