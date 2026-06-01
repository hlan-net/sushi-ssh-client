package net.hlan.sushi

import android.text.Selection
import android.text.Spannable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalViewSelectionTest {

    @Test
    fun selectionIsPreservedDuringUpdate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminalView = TerminalView(context)
        
        // Initial text
        terminalView.appendLog("Hello World\n")
        
        // Select "World" (index 6 to 11)
        Selection.setSelection(terminalView.text as Spannable, 6, 11)
        assertEquals(6, terminalView.selectionStart)
        assertEquals(11, terminalView.selectionEnd)
        
        // Update text with append
        terminalView.appendLog("Another Line\n")
        
        // Selection should be preserved
        assertEquals(6, terminalView.selectionStart)
        assertEquals(11, terminalView.selectionEnd)
    }

    @Test
    fun selectionIsShiftedDuringTrim() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminalView = TerminalView(context)
        
        // Fill up to near limit or just simulate a trim
        // TerminalView has MAX_LINES = 500
        repeat(500) {
            terminalView.appendLog("Line $it\n")
        }
        
        // Select something at the bottom
        val lastLineText = "Line 499\n"
        val start = terminalView.text.length - lastLineText.length
        val end = terminalView.text.length - 1
        Selection.setSelection(terminalView.text as Spannable, start, end)
        
        val oldStart = terminalView.selectionStart
        val oldEnd = terminalView.selectionEnd
        
        // Append one more line, causing trim of "Line 0\n"
        terminalView.appendLog("New Line\n")
        
        // Selection should have shifted left by the length of "Line 0\n"
        val expectedShift = "Line 0\n".length
        assertEquals(oldStart - expectedShift, terminalView.selectionStart)
        assertEquals(oldEnd - expectedShift, terminalView.selectionEnd)
    }
}
