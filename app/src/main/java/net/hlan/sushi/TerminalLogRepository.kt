package net.hlan.sushi

import android.content.Context

class TerminalLogRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLog(content: String) {
        prefs.edit().putString(KEY_LOG, content).apply()
    }

    fun getLog(): String = prefs.getString(KEY_LOG, "").orEmpty()

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
    }

    companion object {
        private const val PREFS_NAME = "sushi_terminal_logs"
        private const val KEY_LOG = "latest_terminal_log"
    }
}
