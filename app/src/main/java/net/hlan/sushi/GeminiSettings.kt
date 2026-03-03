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

    /** Returns the cloud model ID to use (Pro or Flash). Defaults to Pro. */
    fun getCloudModel(): String =
        prefs.getString(KEY_CLOUD_MODEL, GeminiClient.MODEL_PRO) ?: GeminiClient.MODEL_PRO

    fun setCloudModel(modelId: String) {
        prefs.edit().putString(KEY_CLOUD_MODEL, modelId).apply()
    }

    /** Whether to prefer on-device Gemini Nano over the cloud model. Defaults to true. */
    fun getNanoPreferred(): Boolean = prefs.getBoolean(KEY_NANO_PREFERRED, true)

    fun setNanoPreferred(preferred: Boolean) {
        prefs.edit().putBoolean(KEY_NANO_PREFERRED, preferred).apply()
    }

    companion object {
        private const val KEY_ENABLED = "gemini_enabled"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_CLOUD_MODEL = "gemini_cloud_model"
        private const val KEY_NANO_PREFERRED = "gemini_nano_preferred"
    }
}
