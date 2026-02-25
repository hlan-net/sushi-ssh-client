package net.hlan.sushi

import android.content.Context

class GeminiSettings(context: Context) {
    private val prefs = SecurePrefs.get(context)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    companion object {
        private const val KEY_ENABLED = "gemini_enabled"
        private const val KEY_API_KEY = "gemini_api_key"
    }
}
