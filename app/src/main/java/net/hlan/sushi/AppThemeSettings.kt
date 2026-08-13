package net.hlan.sushi

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AppThemeSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val raw = prefs.getInt(KEY_THEME_MODE, ThemeMode.AUTO.storageValue)
        return ThemeMode.fromStorageValue(raw)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode.storageValue).apply()
        applyThemeMode(mode)
    }

    fun applyThemeMode() {
        applyThemeMode(getThemeMode())
    }

    private fun applyThemeMode(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun getAccentVariant(): AccentVariant {
        val raw = prefs.getInt(KEY_ACCENT_VARIANT, AccentVariant.GARI_AMBER.storageValue)
        return AccentVariant.fromStorageValue(raw)
    }

    fun setAccentVariant(variant: AccentVariant) {
        prefs.edit().putInt(KEY_ACCENT_VARIANT, variant.storageValue).apply()
    }

    /** Applies the current accent color choice to this Activity's theme. Call after
     * super.onCreate() and before setContentView() so buttons/tabs pick it up on first draw. */
    fun applyAccentOverlay(activity: Activity) {
        activity.theme.applyStyle(getAccentVariant().overlayStyleRes, true)
    }

    fun getTerminalFontSize(): TerminalFontSize {
        val raw = prefs.getInt(KEY_TERMINAL_FONT_SIZE, TerminalFontSize.MEDIUM.storageValue)
        return TerminalFontSize.fromStorageValue(raw)
    }

    fun setTerminalFontSize(size: TerminalFontSize) {
        prefs.edit().putInt(KEY_TERMINAL_FONT_SIZE, size.storageValue).apply()
    }

    enum class ThemeMode(val storageValue: Int, val nightMode: Int) {
        AUTO(0, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT(1, AppCompatDelegate.MODE_NIGHT_NO),
        DARK(2, AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun fromStorageValue(value: Int): ThemeMode {
                return entries.firstOrNull { it.storageValue == value } ?: AUTO
            }
        }
    }

    enum class AccentVariant(val storageValue: Int, val overlayStyleRes: Int) {
        CORAL(0, R.style.ThemeOverlay_Sushi_Coral),
        WASABI(1, R.style.ThemeOverlay_Sushi_Wasabi),
        GARI_AMBER(2, R.style.ThemeOverlay_Sushi_GariAmber),
        TERRACOTTA(3, R.style.ThemeOverlay_Sushi_Terracotta);

        companion object {
            fun fromStorageValue(value: Int): AccentVariant {
                return entries.firstOrNull { it.storageValue == value } ?: GARI_AMBER
            }
        }
    }

    enum class TerminalFontSize(val storageValue: Int, val sp: Float) {
        SMALL(0, 12f),
        MEDIUM(1, 14f),
        LARGE(2, 16f),
        XL(3, 20f);

        companion object {
            fun fromStorageValue(value: Int): TerminalFontSize {
                return entries.firstOrNull { it.storageValue == value } ?: MEDIUM
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "app_theme"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT_VARIANT = "accent_variant"
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
    }
}
