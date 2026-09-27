package net.hlan.sushi.conversation

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.google.android.material.color.MaterialColors
import net.hlan.sushi.R
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

/**
 * Wraps a Compose screen in a [MaterialTheme] built from the app's existing Views theme
 * (`Theme.Sushi`, `values(-night)/colors.xml`) rather than new Compose-only tokens, so a
 * Compose screen and the legacy screens around it read as one app. `colorPrimary` is resolved
 * through [MaterialColors] rather than a plain color resource because
 * [net.hlan.sushi.AppThemeSettings] overlays it per the user's accent choice at the Activity
 * level before this ever runs; [colorResource] already picks the right `values(-night)` entry
 * on its own, day or dark, so only the [ColorScheme] shape (which sets the defaults for every
 * role left unspecified below) needs to know which mode is active.
 *
 * Every hex value the Figma card's low-fidelity sketch used (`#2f7d4e` for the accent
 * included — the *Foundations* page's stale, unused swatch that `docs/process/UX_PROPOSALS.md`
 * explicitly warns against) is intentionally not reproduced here; the card is right about which
 * elements exist and their structure, not their pixels.
 */
@Composable
fun SushiComposeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val primary = context.themeColor(AppCompatR.attr.colorPrimary)
    val onPrimary = context.themeColor(MaterialR.attr.colorOnPrimary)
    val background = context.themeColor(MaterialR.attr.colorSurface)
    val onSurface = context.themeColor(MaterialR.attr.colorOnSurface)
    val cardSurface = colorResource(R.color.sushi_white)
    val outline = colorResource(R.color.sushi_card_stroke)
    val muted = colorResource(R.color.sushi_slate)

    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            background = background,
            onBackground = onSurface,
            surface = cardSurface,
            onSurface = onSurface,
            outline = outline,
            secondary = muted
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            background = background,
            onBackground = onSurface,
            surface = cardSurface,
            onSurface = onSurface,
            outline = outline,
            secondary = muted
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun Context.themeColor(attrResId: Int): Color =
    Color(MaterialColors.getColor(this, attrResId, Color.Black.toArgb()))
