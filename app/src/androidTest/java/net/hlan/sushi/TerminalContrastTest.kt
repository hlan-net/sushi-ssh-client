package net.hlan.sushi

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
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
 *
 * Two surfaces matter and they are not the same colour. The screen is `sushi_terminal_bg` and
 * carries the key labels and the status line; `TerminalView` sits on `sushi_terminal_panel` and
 * carries the terminal text and everything ANSI. An earlier version of this test measured the
 * ANSI palette against the screen, which is not where any of it is drawn — in light mode that
 * passed `sushi_ansi_bg_white` at `#FFFFFF`, 1.20:1 from the screen but 1.06:1 from the panel it
 * is actually painted on. [outputSurface] now reads the colour off the inflated layout instead of
 * naming a resource, so the palette can only ever be measured against the surface the app uses.
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
        return ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_Sushi)
    }

    /**
     * The colour `TerminalView` is actually drawn on, taken from the layout rather than assumed.
     *
     * This is the point of the class doc above: if the view's background is ever changed, every
     * ANSI assertion below re-measures against the new surface instead of quietly carrying on
     * against a colour nothing uses.
     */
    private fun outputSurface(context: Context): Int {
        val root = LayoutInflater.from(context).inflate(R.layout.activity_terminal, null)
        val output: View = root.findViewById(R.id.terminalOutputText)
        val background = output.background
        // A themed or drawable background would make "the colour behind the text" ambiguous, and
        // every assertion here rests on that colour. Say so, rather than dying on a cast.
        assertTrue(
            "terminalOutputText's background is ${background?.javaClass?.simpleName ?: "null"}, " +
                "not a solid colour — these contrast assertions need one surface to measure against",
            background is ColorDrawable
        )
        return (background as ColorDrawable).color
    }

    private fun screen(context: Context): Int = context.getColor(R.color.sushi_terminal_bg)

    /** The reported bug: a key label on the screen behind the terminal. */
    @Test
    fun keyLabelsAreReadableOnTheScreenBackground() {
        assertContrast("light mode key label", light, R.color.sushi_ink, screen(light), BODY_TEXT_MINIMUM)
        assertContrast("dark mode key label", night, R.color.sushi_ink, screen(night), BODY_TEXT_MINIMUM)
    }

    /** `sushi_terminal_text` is used on both surfaces — the status line and the terminal itself. */
    @Test
    fun terminalTextIsReadableOnBothSurfaces() {
        assertContrast("light mode status line", light, R.color.sushi_terminal_text, screen(light), BODY_TEXT_MINIMUM)
        assertContrast("dark mode status line", night, R.color.sushi_terminal_text, screen(night), BODY_TEXT_MINIMUM)
        assertContrast("light mode terminal text", light, R.color.sushi_terminal_text, outputSurface(light), BODY_TEXT_MINIMUM)
        assertContrast("dark mode terminal text", night, R.color.sushi_terminal_text, outputSurface(night), BODY_TEXT_MINIMUM)
    }

    /**
     * ANSI black is the exception, and only in dark mode: a terminal's black is the dim colour,
     * meant to recede rather than to read as body text. Everything else carries body-text
     * contrast, which the raw `Color` constants could not do on a light background — pure yellow
     * and white both landed near 1.1:1.
     */
    @Test
    fun everyAnsiColourIsReadableInLightMode() {
        val surface = outputSurface(light)
        for (index in ANSI_COLORS.indices) {
            assertContrast("light mode ANSI $index", light, ANSI_COLORS[index], surface, BODY_TEXT_MINIMUM)
        }
    }

    @Test
    fun everyAnsiColourIsReadableInDarkMode() {
        val surface = outputSurface(night)
        for (index in ANSI_COLORS.indices) {
            val floor = if (index == ANSI_BLACK) DIM_MINIMUM else BODY_TEXT_MINIMUM
            assertContrast("dark mode ANSI $index", night, ANSI_COLORS[index], surface, floor)
        }
    }

    /**
     * Both surfaces have to actually change with the theme. Pinning either to one shade is what
     * caused the original bug, and would leave every assertion above passing in one mode only.
     */
    @Test
    fun bothSurfacesFollowTheTheme() {
        assertNotEquals("the screen is the same in both themes", screen(light), screen(night))
        assertNotEquals("the output surface is the same in both themes", outputSurface(light), outputSurface(night))
        assertTrue("light mode screen is not light: ${hex(screen(light))}", luminance(screen(light)) > 0.5)
        assertTrue("dark mode screen is not dark: ${hex(screen(night))}", luminance(screen(night)) < 0.1)
        assertTrue("light mode panel is not light: ${hex(outputSurface(light))}", luminance(outputSurface(light)) > 0.5)
        assertTrue("dark mode panel is not dark: ${hex(outputSurface(night))}", luminance(outputSurface(night)) < 0.1)
    }

    // --- SGR 40-47: text painted on an ANSI background ---

    /**
     * The second half of the same bug. The foreground palette was also being used for
     * backgrounds, and those entries are chosen to be read *on* the output surface — so in
     * dark mode `ESC[47m` painted `#E6F1ED` behind `#E6F1ED` text, and in light mode `ESC[40m`
     * painted `#0E1B16` behind `#0E1B16`. Both measured exactly 1.00:1: a blank rectangle where
     * the output should have been.
     */
    @Test
    fun defaultTerminalTextIsReadableOnEveryAnsiBackground() {
        for (index in ANSI_BACKGROUNDS.indices) {
            assertOnAnsiBackground("light mode ANSI bg $index", light, index)
            assertOnAnsiBackground("dark mode ANSI bg $index", night, index)
        }
    }

    /** A background nobody can tell from the surface under it highlights nothing. */
    @Test
    fun everyAnsiBackgroundIsDistinctFromTheOutputSurface() {
        assertBackgroundsAreDistinct("light", light)
        assertBackgroundsAreDistinct("dark", night)
    }

    private fun assertBackgroundsAreDistinct(mode: String, context: Context) {
        val surface = outputSurface(context)
        for (index in ANSI_BACKGROUNDS.indices) {
            val background = context.getColor(ANSI_BACKGROUNDS[index])
            val measured = contrast(background, surface)
            assertTrue(
                "$mode mode ANSI bg $index: ${hex(background)} is %.2f:1 from the output surface ${hex(surface)}, under %.1f:1"
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

    private fun assertOnAnsiBackground(what: String, context: Context, bgIndex: Int) {
        val foreground = context.getColor(R.color.sushi_terminal_text)
        val background = context.getColor(ANSI_BACKGROUNDS[bgIndex])
        val measured = contrast(foreground, background)
        assertTrue(
            "$what: ${hex(foreground)} on ${hex(background)} is %.2f:1, under %.1f:1"
                .format(measured, BODY_TEXT_MINIMUM),
            measured >= BODY_TEXT_MINIMUM
        )
    }

    private fun assertContrast(what: String, context: Context, colorRes: Int, background: Int, minimum: Double) {
        val foreground = context.getColor(colorRes)
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
         * that had to clear 3:1 against the output surface could not also keep the text on it
         * readable.
         */
        const val HIGHLIGHT_MINIMUM = 1.1
    }
}
