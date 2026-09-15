package net.hlan.sushi

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the terminal's key rows at phone width.
 *
 * The cursor keys were nearly added to the Enter / Tab / Backspace row, where "Send Backspace"
 * already wraps to two lines at three buttons. Five would have squeezed the row rather than
 * failing anywhere a build could see it, so the arrows got a row of their own — and this holds
 * that: every key stays at least a 48dp touch target, and no row is wider than the screen.
 */
@RunWith(AndroidJUnit4::class)
class TerminalKeyRowLayoutTest {

    private lateinit var context: Context
    private lateinit var root: View

    @Before
    fun setUp() {
        context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_Sushi
        )
        root = LayoutInflater.from(context).inflate(R.layout.activity_terminal, null)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(PHONE_WIDTH_DP), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(PHONE_HEIGHT_DP), View.MeasureSpec.AT_MOST)
        )
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    private fun button(id: Int): View = root.findViewById(id)

    @Test
    fun everyKeyIsAtLeastATouchTarget() {
        val keys = mapOf(
            "arrow up" to R.id.terminalArrowUpButton,
            "arrow down" to R.id.terminalArrowDownButton,
            "enter" to R.id.terminalEnterButton,
            "tab" to R.id.terminalTabButton,
            "backspace" to R.id.terminalBackspaceButton
        )
        val minimum = dp(MIN_TOUCH_TARGET_DP)

        for ((name, id) in keys) {
            val width = button(id).measuredWidth
            assertTrue(
                "$name measured ${width}px at ${PHONE_WIDTH_DP}dp, below the ${MIN_TOUCH_TARGET_DP}dp target",
                width >= minimum
            )
        }
    }

    /** The arrows share a row with nothing else, so each gets about half the width. */
    @Test
    fun theArrowsShareTheirRowEvenly() {
        val up = button(R.id.terminalArrowUpButton).measuredWidth
        val down = button(R.id.terminalArrowDownButton).measuredWidth

        assertTrue("arrows differ in width: $up vs $down", Math.abs(up - down) <= dp(1))
        assertTrue("arrows are unexpectedly narrow: $up", up >= dp(120))
    }

    /** No key row may run off the side of the screen. */
    @Test
    fun noKeyRowOverflowsTheScreen() {
        val screen = dp(PHONE_WIDTH_DP)
        for (row in keyRows()) {
            assertTrue(
                "a key row measured ${row.measuredWidth}px against a ${screen}px screen",
                row.measuredWidth <= screen
            )
        }
    }

    private fun keyRows(): List<ViewGroup> {
        val rows = mutableListOf<ViewGroup>()
        for (id in listOf(R.id.terminalArrowUpButton, R.id.terminalEnterButton, R.id.terminalCtrlCButton)) {
            val parent = button(id).parent
            if (parent is ViewGroup) {
                rows += parent
            }
        }
        return rows
    }

    private companion object {
        /** A small phone in portrait — the narrowest case the app is expected to fit. */
        const val PHONE_WIDTH_DP = 360
        const val PHONE_HEIGHT_DP = 640
        const val MIN_TOUCH_TARGET_DP = 48
    }
}
