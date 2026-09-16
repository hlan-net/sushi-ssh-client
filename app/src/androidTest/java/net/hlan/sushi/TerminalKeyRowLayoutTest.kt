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

    /**
     * Written without mapOf/listOf on purpose: these run against the R8'd app APK, which is
     * where the test APK resolves the Kotlin stdlib from, and R8 drops the parts the app itself
     * never calls. An earlier version of this test died on
     * `NoClassDefFoundError: kotlin.collections.MapsKt` rather than on anything it meant to check.
     */
    @Test
    fun everyKeyIsAtLeastATouchTarget() {
        assertIsATouchTarget("arrow up", R.id.terminalArrowUpButton)
        assertIsATouchTarget("arrow down", R.id.terminalArrowDownButton)
        assertIsATouchTarget("enter", R.id.terminalEnterButton)
        assertIsATouchTarget("tab", R.id.terminalTabButton)
        assertIsATouchTarget("backspace", R.id.terminalBackspaceButton)
    }

    private fun assertIsATouchTarget(name: String, id: Int) {
        val width = button(id).measuredWidth
        assertTrue(
            "$name measured ${width}px at ${PHONE_WIDTH_DP}dp, below the ${MIN_TOUCH_TARGET_DP}dp target",
            width >= dp(MIN_TOUCH_TARGET_DP)
        )
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
        assertRowFits(R.id.terminalArrowUpButton)
        assertRowFits(R.id.terminalEnterButton)
        assertRowFits(R.id.terminalCtrlCButton)
    }

    /** Named by a key inside it, since the rows themselves have no ids. */
    private fun assertRowFits(keyId: Int) {
        val row = button(keyId).parent as ViewGroup
        val screen = dp(PHONE_WIDTH_DP)
        assertTrue(
            "a key row measured ${row.measuredWidth}px against a ${screen}px screen",
            row.measuredWidth <= screen
        )
    }

    private companion object {
        /** A small phone in portrait — the narrowest case the app is expected to fit. */
        const val PHONE_WIDTH_DP = 360
        const val PHONE_HEIGHT_DP = 640
        const val MIN_TOUCH_TARGET_DP = 48
    }
}
