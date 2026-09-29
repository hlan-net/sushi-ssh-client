package net.hlan.sushi

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * What the user picked in Settings, independent of any concrete model id. `GeminiClient.kt`
 * used to hard-code `gemini-2.5-pro` / `gemini-1.5-flash`; ids like that age out in 12-18 months
 * (`docs/process/DEPENDENCY_LIFECYCLE.md` §1) and did — `gemini-1.5-flash` no longer exists at
 * all. This is what survives that: a preference for cost/speed, resolved to whatever id the API
 * currently offers by [GeminiModelResolver].
 */
enum class GeminiModelCapability(val storageValue: String) {
    CAPABLE("capable"),
    FAST("fast");

    companion object {
        val DEFAULT = CAPABLE

        /**
         * Parses [raw] as stored by [GeminiSettings]. Also accepts the raw model id strings the
         * same preference key used to hold before this type existed (`gemini-2.5-pro`,
         * `gemini-1.5-flash`, …), so an existing install's choice of Flash carries over as [FAST]
         * rather than silently reverting to the [DEFAULT].
         */
        fun from(raw: String?): GeminiModelCapability = when {
            raw == null -> DEFAULT
            raw.equals(FAST.storageValue, ignoreCase = true) -> FAST
            raw.equals(CAPABLE.storageValue, ignoreCase = true) -> CAPABLE
            "flash" in raw -> FAST
            else -> DEFAULT
        }
    }
}

/**
 * Picks a concrete model id for a [GeminiModelCapability] out of whatever [GeminiModelCatalog]
 * currently has. Pure and Android-free so it is JVM-testable against a fake list — the point of
 * the exercise: a wholesale model-line rename must still resolve to *something* reasonable
 * rather than requiring a code change.
 */
object GeminiModelResolver {
    /** Used only when [availableModelIds] has nothing recognisable — no network yet and no cache. */
    private val FALLBACK_IDS = mapOf(
        GeminiModelCapability.CAPABLE to "gemini-3.1-pro-preview",
        GeminiModelCapability.FAST to "gemini-3.5-flash"
    )

    /** Ids excluded even when they otherwise match the keyword: specialised variants, not chat models. */
    private val EXCLUDED_SUBSTRINGS = listOf(
        "lite", "image", "tts", "live", "embedding", "vision", "audio", "thinking", "banana"
    )

    fun resolve(capability: GeminiModelCapability, availableModelIds: List<String>): String {
        val keyword = if (capability == GeminiModelCapability.FAST) "flash" else "pro"
        val candidates = availableModelIds.filter { id ->
            keyword in id && EXCLUDED_SUBSTRINGS.none { it in id }
        }
        if (candidates.isEmpty()) {
            return FALLBACK_IDS.getValue(capability)
        }
        // Highest version wins even if it's a preview: "preview" is not a synonym for "worse" in
        // this API — a generation can ship preview-only for a long stretch while the previous
        // stable one is wound down (2.5 is access-restricted to prior users as of this writing).
        // "Preview" only breaks a tie at the same version, where the stable release is the
        // sensible default.
        val bestVersion = candidates.maxOf { versionOf(it) }
        val atBestVersion = candidates.filter { versionOf(it) == bestVersion }
        return atBestVersion.firstOrNull { "preview" !in it } ?: atBestVersion.first()
    }

    /** The leading `gemini-<version>` number, e.g. `3.1` out of `gemini-3.1-pro-preview`. 0 if absent. */
    private fun versionOf(id: String): Double =
        VERSION_PATTERN.find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private val VERSION_PATTERN = Regex("gemini-(\\d+(?:\\.\\d+)?)")
}

/**
 * The model ids the Gemini API currently offers (`GET /v1beta/models`), cached in
 * [GeminiSettings] and refreshed at most weekly — a full fetch on every request would be one
 * more round trip before every conversation turn, for a list that barely changes day to day.
 */
class GeminiModelCatalog(private val settings: GeminiSettings) {

    /**
     * Returns cached ids when they're fresh; otherwise fetches, caching the result on success.
     * A failed fetch falls back to whatever cache exists, however stale, rather than an empty
     * list — a network blip must not make model resolution forget every id it ever saw.
     */
    fun getModelIds(apiKey: String, accessToken: String?): List<String> {
        val cached = settings.getCachedModelIds()
        if (cached != null && !settings.isModelCacheStale()) {
            return cached
        }
        val fetched = runCatching { fetchModelIds(apiKey, accessToken) }.getOrNull()
        if (!fetched.isNullOrEmpty()) {
            settings.setCachedModelIds(fetched)
            return fetched
        }
        return cached.orEmpty()
    }

    private fun fetchModelIds(apiKey: String, accessToken: String?): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append(LIST_MODELS_URL)
                append("?pageSize=200")
                if (accessToken == null) append("&key=").append(apiKey)
                pageToken?.let { append("&pageToken=").append(it) }
            }
            val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val body = try {
                readBody(connection)
            } finally {
                connection.disconnect()
            }
            val json = JSONObject(body)
            val models = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val name = models.optJSONObject(i)?.optString("name").orEmpty()
                if (name.startsWith(MODEL_NAME_PREFIX)) {
                    ids += name.removePrefix(MODEL_NAME_PREFIX)
                }
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return ids
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream.bufferedReader().use { it.readText() }
    }

    companion object {
        private const val LIST_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL_NAME_PREFIX = "models/"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
