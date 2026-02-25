package net.hlan.sushi

import android.content.Context

class ConsoleLogRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun appendLine(line: String) {
        val current = prefs.getString(KEY_LOG, "") ?: ""
        val updated = if (current.isBlank()) {
            line
        } else {
            "$current\n$line"
        }
        prefs.edit().putString(KEY_LOG, updated).apply()
    }

    fun getLog(): String = prefs.getString(KEY_LOG, "") ?: ""

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
    }

    companion object {
        private const val PREFS_NAME = "sushi_console_logs"
        private const val KEY_LOG = "latest_log"
    }
}
