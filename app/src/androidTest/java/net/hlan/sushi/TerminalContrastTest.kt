package net.hlan.sushi

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contrast of the terminal's palette, measured in both themes.
 *
 * The terminal screen used to paint a fixed dark background while its buttons took their colour
 * from the day/night palette, so in light mode every key label was `#0E1B16` on `#0F1514` — 1.04:1,
 * text that is present but cannot be seen. Only Ctrl+C escaped it, by overriding with
 * `?attr/colorError`. The terminal now follows the theme, and these hold it there.
 *
 * Both modes are measured in one run through a configuration-overridden Context, so neither
 * depends on how the device running the suite happens to be set.
 */
@RunWith(AndroidJUnit4::class)
class TerminalContrastTest {

    private lateinit var light: Context
    private lateinit var night: Context

    @Before
    fun setUp() {
        light = contextForNightMode(Configuration.UI_MODE_NIGHT_NO)
        night = contextForNightMode(Configuration.UI_MODE_NIGHT_YES)
    }

    private fun contextForNightMode(nightMode: Int): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        return base.createConfigurationContext(config)
    }

    /** The reported bug: a key label on the terminal background. */
    @Test
    fun keyLabelsAreReadableOnTheTerminalBackground() {
        assertContrast("light mode key label", light, R.color.sushi_ink, BODY_TEXT_MINIMUM)
        assertContrast("dark mode key label", night, R.color.sushi_ink, BODY_TEXT_MINIMUM)
    }

    @Test
    fun terminalTextIsReadableInBothModes() {
        assertContrast("light mode terminal text", light, R.color.sushi_terminal_text, BODY_TEXT_MINIMUM)
        assertContrast("dark mode terminal text", night, R.color.sushi_terminal_text, BODY_TEXT_MINIMUM)
    }

    /**
     * ANSI black is the exception, and only in dark mode: a terminal's black is the dim colour,
     * meant to recede rather than to read as body text. Everything else carries body-text
     * contrast, which the raw `Color` constants could not do on a light background — pure yellow
     * and white both landed near 1.1:1.
     */
    @Test
    fun everyAnsiColourIsReadableInLightMode() {
        for (index in ANSI_COLORS.indices) {
            assertContrast("light mode ANSI $index", light, ANSI_COLORS[index], BODY_TEXT_MINIMUM)
        }
    }

    @Test
    fun everyAnsiColourIsReadableInDarkMode() {
        for (index in ANSI_COLORS.indices) {
            val floor = if (index == ANSI_BLACK) DIM_MINIMUM else BODY_TEXT_MINIMUM
            assertContrast("dark mode ANSI $index", night, ANSI_COLORS[index], floor)
        }
    }

    /**
     * The background has to actually change with the theme. Pinning it to one shade is what
     * caused the original bug, and would leave every assertion above passing in one mode only.
     */
    @Test
    fun theTerminalBackgroundFollowsTheTheme() {
        val lightBg = light.getColor(R.color.sushi_terminal_bg)
        val nightBg = night.getColor(R.color.sushi_terminal_bg)

        assertNotEquals("the terminal background is the same in both themes", lightBg, nightBg)
        assertTrue("light mode background is not light: ${hex(lightBg)}", luminance(lightBg) > 0.5)
        assertTrue("dark mode background is not dark: ${hex(nightBg)}", luminance(nightBg) < 0.1)
    }

    // --- SGR 40-47: text painted on an ANSI background ---

    /**
     * The second half of the same bug. The foreground palette was also being used for
     * backgrounds, and those entries are chosen to be read *on* `sushi_terminal_bg` — so in
     * dark mode `ESC[47m` painted `#E6F1ED` behind `#E6F1ED` text, and in light mode `ESC[40m`
     * painted `#0E1B16` behind `#0E1B16`. Both measured exactly 1.00:1: a blank rectangle where
     * the output should be.
     */
    @Test
    fun defaultTerminalTextIsReadableOnEveryAnsiBackground() {
        for (index in ANSI_BACKGROUNDS.indices) {
            assertOnBackground("light mode ANSI bg $index", light, R.color.sushi_terminal_text, index)
            assertOnBackground("dark mode ANSI bg $index", night, R.color.sushi_terminal_text, index)
        }
    }

    /** A background nobody can tell from the terminal background highlights nothing. */
    @Test
    fun everyAnsiBackgroundIsDistinctFromTheTerminalBackground() {
        assertBackgroundsAreDistinct("light", light)
        assertBackgroundsAreDistinct("dark", night)
    }

    private fun assertBackgroundsAreDistinct(mode: String, context: Context) {
        val terminal = context.getColor(R.color.sushi_terminal_bg)
        for (index in ANSI_BACKGROUNDS.indices) {
            val background = context.getColor(ANSI_BACKGROUNDS[index])
            val measured = contrast(background, terminal)
            assertTrue(
                "$mode mode ANSI bg $index: ${hex(background)} is %.2f:1 from the terminal background, under %.1f:1"
                    .format(measured, HIGHLIGHT_MINIMUM),
                measured >= HIGHLIGHT_MINIMUM
            )
        }
    }

    /**
     * SGR pairs the two palettes freely — `ESC[30;47m` is a legal thing to emit — and neither
     * palette is tuned against the other, so [TerminalView] shifts the foreground until the pair
     * clears the floor. Every one of the 16 x 8 combinations is checked here in both themes,
     * because the unreadable ones are exactly the pairs nobody would think to try.
     */
    @Test
    fun everyForegroundBackgroundPairIsReadableAfterTheNudge() {
        assertEveryPairIsReadable("light", light)
        assertEveryPairIsReadable("dark", night)
    }

    private fun assertEveryPairIsReadable(mode: String, context: Context) {
        val view = TerminalView(context)
        for (bgCode in 40..47) {
            for (fgCode in 30..37) {
                assertRenderedPairIsReadable(mode, view, fgCode, bgCode)
            }
            for (fgCode in 90..97) {
                assertRenderedPairIsReadable(mode, view, fgCode, bgCode)
            }
        }
    }

    private fun assertRenderedPairIsReadable(mode: String, view: TerminalView, fgCode: Int, bgCode: Int) {
        val rendered = render(view, fgCode, bgCode)
        val measured = contrast(rendered[0], rendered[1])
        assertTrue(
            "$mode mode ESC[$fgCode;${bgCode}m rendered ${hex(rendered[0])} on ${hex(rendered[1])} at %.2f:1"
                .format(measured),
            measured >= BODY_TEXT_MINIMUM
        )
    }

    /** A pair that already reads must be left exactly as the remote asked for it. */
    @Test
    fun aReadablePairIsNotNudged() {
        // 97 is bright white, 44 a dark blue background: legible as sent, so nothing to shift.
        val rendered = render(TerminalView(night), 97, 44)

        assertEquals(night.getColor(R.color.sushi_ansi_bright_white), rendered[0])
        assertEquals(night.getColor(R.color.sushi_ansi_bg_blue), rendered[1])
    }

    /**
     * Renders one SGR pair and reads the colours back off the spans.
     *
     * Goes through [TerminalView.appendLog] rather than calling the contrast helper directly:
     * that helper is private, and these tests run against the minified APK where R8 is free to
     * rename or inline it. The rendered spans are also what actually reaches the screen, which
     * is the thing worth asserting.
     *
     * Returns the foreground and the background, in that order.
     */
    private fun render(view: TerminalView, fgCode: Int, bgCode: Int): IntArray {
        view.clearLog()
        view.appendLog("\u001B[$fgCode;${bgCode}mX")

        val spanned = view.text as Spanned
        val foreground = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
        val background = spanned.getSpans(0, spanned.length, BackgroundColorSpan::class.java)
        assertEquals("ESC[$fgCode;${bgCode}m set no foreground", 1, foreground.size)
        assertEquals("ESC[$fgCode;${bgCode}m set no background", 1, background.size)
        return intArrayOf(foreground[0].foregroundColor, background[0].backgroundColor)
    }

    private fun assertOnBackground(what: String, context: Context, colorRes: Int, bgIndex: Int) {
        val foreground = context.getColor(colorRes)
        val background = context.getColor(ANSI_BACKGROUNDS[bgIndex])
        val measured = contrast(foreground, background)
        assertTrue(
            "$what: ${hex(foreground)} on ${hex(background)} is %.2f:1, under %.1f:1"
                .format(measured, BODY_TEXT_MINIMUM),
            measured >= BODY_TEXT_MINIMUM
        )
    }

    private fun assertContrast(what: String, context: Context, colorRes: Int, minimum: Double) {
        val foreground = context.getColor(colorRes)
        val background = context.getColor(R.color.sushi_terminal_bg)
        val measured = contrast(foreground, background)
        assertTrue(
            "$what: ${hex(foreground)} on ${hex(background)} is %.2f:1, under %.1f:1"
                .format(measured, minimum),
            measured >= minimum
        )
    }

    private fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    private fun luminance(color: Int): Double =
        0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }

    private companion object {
        /** WCAG 2.1 AA for normal-size text. */
        const val BODY_TEXT_MINIMUM = 4.5

        /** WCAG's non-text floor, which ANSI black only has to clear. */
        const val DIM_MINIMUM = 3.0

        const val ANSI_BLACK = 0

        /**
         * Listed here rather than read from [TerminalView] so the test does not depend on a field
         * surviving R8 — `R.color` ids are compile-time constants and always resolve.
         */
        val ANSI_COLORS = intArrayOf(
            R.color.sushi_ansi_black, R.color.sushi_ansi_red,
            R.color.sushi_ansi_green, R.color.sushi_ansi_yellow,
            R.color.sushi_ansi_blue, R.color.sushi_ansi_magenta,
            R.color.sushi_ansi_cyan, R.color.sushi_ansi_white,
            R.color.sushi_ansi_bright_black, R.color.sushi_ansi_bright_red,
            R.color.sushi_ansi_bright_green, R.color.sushi_ansi_bright_yellow,
            R.color.sushi_ansi_bright_blue, R.color.sushi_ansi_bright_magenta,
            R.color.sushi_ansi_bright_cyan, R.color.sushi_ansi_bright_white
        )

        /** SGR 40-47, in the same ANSI order. No bright half: 100-107 are not handled. */
        val ANSI_BACKGROUNDS = intArrayOf(
            R.color.sushi_ansi_bg_black, R.color.sushi_ansi_bg_red,
            R.color.sushi_ansi_bg_green, R.color.sushi_ansi_bg_yellow,
            R.color.sushi_ansi_bg_blue, R.color.sushi_ansi_bg_magenta,
            R.color.sushi_ansi_bg_cyan, R.color.sushi_ansi_bg_white
        )

        /**
         * A highlight has to be visible as a highlight. Modest on purpose — a background tint
         * that had to clear 3:1 against the terminal background could not also keep the text
         * on it readable.
         */
        const val HIGHLIGHT_MINIMUM = 1.1
    }
}
