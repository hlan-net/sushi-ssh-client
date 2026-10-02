package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [TerminalBuffer.appendLine] — the app's own status lines get a line each.
 *
 * Moved from the instrumented `TerminalViewLogLineTest` when line trimming and the rest of the
 * buffer's state moved into [TerminalBuffer] (`ROADMAP.md` v0.9.0).
 *
 * Status strings carry no newline and [TerminalBuffer.append] only breaks a line when it sees a
 * real `\n`, so every status used to continue whatever was on screen, and the shell prompt that
 * arrived next continued it in turn:
 *
 * `[Terminal] Connecting...Connected to ekho (larry@192.168.1.11:22) · ssh.larry@ekho:~ $`
 *
 * The `ssh.` in the middle of that is the host kind from `displayTarget()` plus the full stop
 * ending "Connected to %1$s.", which together read as a hostname that does not exist.
 */
class TerminalBufferLogLineTest {

    private lateinit var buffer: TerminalBuffer

    @Before
    fun setUp() {
        buffer = TerminalBuffer()
    }

    /** The reported line, end to end. */
    @Test
    fun connectSequence_putsEachStatusAndThePromptOnItsOwnLine() {
        buffer.appendLine("[Terminal] Connecting...")
        buffer.appendLine("Connected to ekho (larry@192.168.1.11:22) · ssh.")
        buffer.append("larry@ekho:~ $ ")

        assertEquals(
            "[Terminal] Connecting...\n" +
                "Connected to ekho (larry@192.168.1.11:22) · ssh.\n" +
                "larry@ekho:~ $ ",
            buffer.text
        )
    }

    @Test
    fun consecutiveStatusLines_eachGetTheirOwnLine() {
        buffer.appendLine("[Terminal] Connecting...")
        buffer.appendLine("[Terminal] Connection failed: Auth cancel for methods 'publickey,password'")

        assertEquals(
            "[Terminal] Connecting...\n" +
                "[Terminal] Connection failed: Auth cancel for methods 'publickey,password'\n",
            buffer.text
        )
    }

    /** A status arriving mid-line — remote output still on the current row — starts a new one. */
    @Test
    fun statusLine_afterUnterminatedRemoteOutput_startsFresh() {
        buffer.append("larry@ekho:~ $ ")
        buffer.appendLine("[Terminal] Connection lost unexpectedly.")

        assertEquals(
            "larry@ekho:~ $ \n[Terminal] Connection lost unexpectedly.\n",
            buffer.text
        )
    }

    /** Remote output that already ends a line must not gain a blank one. */
    @Test
    fun statusLine_afterTerminatedRemoteOutput_addsNoBlankLine() {
        buffer.append("total 0\n")
        buffer.appendLine("[Terminal] Connection lost unexpectedly.")

        assertEquals("total 0\n[Terminal] Connection lost unexpectedly.\n", buffer.text)
    }

    /** Nothing on screen yet: the first status must not be pushed down by a leading break. */
    @Test
    fun firstStatusLine_doesNotOpenWithABlankLine() {
        buffer.appendLine("[Terminal] Connecting...")

        assertEquals("[Terminal] Connecting...\n", buffer.text)
    }

    /**
     * The remote stream keeps its own line discipline — [TerminalBuffer.append] is unchanged, or
     * progress bars and prompt redraws would each land on a line of their own.
     */
    @Test
    fun remoteOutput_isStillAppendedWithoutAddedBreaks() {
        buffer.append("one")
        buffer.append("two")

        assertEquals("onetwo", buffer.text)
    }

    /** A status line carrying its own newline must not end up double-spaced. */
    @Test
    fun statusLineEndingInANewline_isNotTerminatedTwice() {
        buffer.appendLine("[Terminal] Connecting...\n")

        assertEquals("[Terminal] Connecting...\n", buffer.text)
    }
}
