package net.hlan.sushi

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    }
}
