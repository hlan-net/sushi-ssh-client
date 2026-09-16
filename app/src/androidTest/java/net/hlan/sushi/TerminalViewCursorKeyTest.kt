package net.hlan.sushi

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the cursor keys reaching the shell.
 *
 * Before this, [TerminalView]'s input connection handled Enter, Tab and Backspace and dropped
 * everything else, and nothing in the app ever emitted an ANSI cursor sequence — so command
 * history was unreachable however it was pressed, from the on-screen row or a hardware keyboard.
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewCursorKeyTest {

    private lateinit var view: TerminalView
    private val sent = mutableListOf<String>()

    @Before
    fun setUp() {
        view = TerminalView(InstrumentationRegistry.getInstrumentation().targetContext)
        view.onInputText = { sent += it }
    }

    private fun pressKey(keyCode: Int) {
        val connection = view.onCreateInputConnection(EditorInfo())
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }

    /**
     * The sequences xterm sends with the cursor keypad in normal mode. Pinned because a typo
     * would reach the shell as printable junk rather than failing anywhere visible.
     */
    @Test
    fun cursorSequences_areTheNormalModeXtermOnes() {
        assertEquals("[A", TerminalView.CURSOR_UP)
        assertEquals("[B", TerminalView.CURSOR_DOWN)
    }

    @Test
    fun dpadUp_sendsCursorUp() {
        pressKey(KeyEvent.KEYCODE_DPAD_UP)

        assertEquals(listOf("[A"), sent)
    }

    @Test
    fun dpadDown_sendsCursorDown() {
        pressKey(KeyEvent.KEYCODE_DPAD_DOWN)

        assertEquals(listOf("[B"), sent)
    }

    /** Key-up must not double the keystroke: the connection only acts on ACTION_DOWN. */
    @Test
    fun keyUpEvent_sendsNothing() {
        val connection = view.onCreateInputConnection(EditorInfo())
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))

        assertTrue(sent.isEmpty())
    }

    /** The keys that already worked keep working. */
    @Test
    fun enterTabAndBackspace_areUnchanged() {
        pressKey(KeyEvent.KEYCODE_ENTER)
        pressKey(KeyEvent.KEYCODE_TAB)
        pressKey(KeyEvent.KEYCODE_DEL)

        assertEquals(listOf("\n", "\t", "\b"), sent)
    }

    // --- the path a physical keyboard actually takes ---

    /**
     * A hardware key is dispatched to the focused view, not through the input connection, and
     * this view is a selectable text view with a movement method — both of which answer the
     * arrows themselves. Dispatching for real is the only way to see that the terminal claims
     * the key first.
     */
    @Test
    fun dispatchedArrowKeys_reachTheShell() {
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))

        assertEquals(listOf("\u001B[A", "\u001B[B"), sent)
    }

    @Test
    fun dispatchedEnterTabAndBackspace_reachTheShell() {
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertEquals(listOf("\n", "\t", "\b"), sent)
    }

    /**
     * With no shell attached the view is just a log, so the arrows must fall through to the
     * movement method and scroll it rather than being swallowed.
     */
    @Test
    fun withoutAShell_arrowKeysAreLeftToTheTextView() {
        view.onInputText = null

        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))

        assertTrue(sent.isEmpty())
    }

    /**
     * Left and right are deliberately still dropped. The shell would handle them, but
     * [TerminalView] is a line buffer with no cursor of its own, so a mid-line edit would send
     * the right command while drawing the wrong line. They come with cursor emulation.
     */
    @Test
    fun horizontalArrows_areNotSentYet() {
        pressKey(KeyEvent.KEYCODE_DPAD_LEFT)
        pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))

        assertTrue(sent.isEmpty())
    }
}
