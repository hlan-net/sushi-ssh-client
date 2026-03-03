package net.hlan.sushi

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
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
    }
}
