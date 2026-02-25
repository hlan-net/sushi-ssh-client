package net.hlan.sushi

import android.content.Context

class DriveLogSettings(context: Context) {
    private val prefs = SecurePrefs.get(context)

    fun isAlwaysSaveEnabled(): Boolean = prefs.getBoolean(KEY_ALWAYS_SAVE, false)

    fun setAlwaysSaveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_SAVE, enabled).apply()
    }

    companion object {
        private const val KEY_ALWAYS_SAVE = "drive_logs_always_save"
    }
}
