package net.hlan.sushi

import android.content.Context
import org.json.JSONArray

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

    /** The user's cloud-model preference — Capable (Pro) or Fast (Flash) — not a concrete id. */
    fun getModelCapability(): GeminiModelCapability = GeminiModelCapability.from(prefs.getString(KEY_CLOUD_MODEL, null))

    fun setModelCapability(capability: GeminiModelCapability) {
        prefs.edit().putString(KEY_CLOUD_MODEL, capability.storageValue).apply()
    }

    /**
     * [GeminiModelCatalog]'s cache of model ids `GET /v1beta/models` last returned, if any —
     * null when it was fetched with a credential other than [credentialId].
     */
    fun getCachedModelIds(credentialId: String): List<String>? {
        if (prefs.getString(KEY_MODEL_CACHE_OWNER, null) != credentialId) return null
        val json = prefs.getString(KEY_MODEL_CACHE_IDS, null) ?: return null
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        }.getOrNull()
    }

    fun setCachedModelIds(ids: List<String>, credentialId: String) {
        prefs.edit()
            .putString(KEY_MODEL_CACHE_IDS, JSONArray(ids).toString())
            .putString(KEY_MODEL_CACHE_OWNER, credentialId)
            .putLong(KEY_MODEL_CACHE_AT, System.currentTimeMillis())
            .apply()
    }

    /** True once the cache is older than [MODEL_CACHE_TTL_MS], or was never populated. */
    fun isModelCacheStale(): Boolean {
        val cachedAt = prefs.getLong(KEY_MODEL_CACHE_AT, 0L)
        return System.currentTimeMillis() - cachedAt > MODEL_CACHE_TTL_MS
    }

    /** Whether to prefer on-device Gemini Nano over the cloud model. Defaults to true. */
    fun getNanoPreferred(): Boolean = prefs.getBoolean(KEY_NANO_PREFERRED, true)

    fun setNanoPreferred(preferred: Boolean) {
        prefs.edit().putBoolean(KEY_NANO_PREFERRED, preferred).apply()
    }

    /**
     * Whether the AI may chain follow-up commands automatically while troubleshooting
     * (roadmap v0.8.0). Safety classification still applies to every step, so CONFIRM-tier
     * commands stop the chain and ask. Defaults to true.
     */
    fun getAutoTroubleshootEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_TROUBLESHOOT, true)

    fun setAutoTroubleshootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TROUBLESHOOT, enabled).apply()
    }

    companion object {
        private const val KEY_ENABLED = "gemini_enabled"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_CLOUD_MODEL = "gemini_cloud_model"
        private const val KEY_NANO_PREFERRED = "gemini_nano_preferred"
        private const val KEY_AUTO_TROUBLESHOOT = "gemini_auto_troubleshoot"
        private const val KEY_MODEL_CACHE_IDS = "gemini_model_cache_ids"
        private const val KEY_MODEL_CACHE_AT = "gemini_model_cache_at"
        private const val KEY_MODEL_CACHE_OWNER = "gemini_model_cache_owner"

        /** A week: model ids don't change often enough to justify fetching more eagerly than this. */
        private const val MODEL_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
