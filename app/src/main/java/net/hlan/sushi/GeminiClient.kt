package net.hlan.sushi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

class GeminiClient(
    private val context: Context,
    private val settings: GeminiSettings,
    private val authManager: DriveAuthManager? = null
) {
    /**
     * Returns the current auth mode that will be used for the next request.
     * Google account takes priority over API key when signed in.
     */
    fun getAuthMode(): AuthMode {
        if (authManager?.getSignedInAccount() != null) {
            return AuthMode.GOOGLE_ACCOUNT
        }
        val apiKey = settings.getApiKey().trim()
        if (apiKey.isNotEmpty()) {
            return AuthMode.API_KEY
        }
        return AuthMode.NONE
    }

    fun generateCommand(userPrompt: String): GeminiResult {
        val authMode = getAuthMode()
        if (authMode == AuthMode.NONE) {
            return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
        }

        val connection = when (authMode) {
            AuthMode.GOOGLE_ACCOUNT -> {
                val token = authManager?.getAccessToken()
                if (token == null) {
                    // Token retrieval failed — fall back to API key if available
                    val apiKey = settings.getApiKey().trim()
                    if (apiKey.isEmpty()) {
                        return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
                    }
                    createApiKeyConnection(apiKey)
                } else {
                    createOAuthConnection(token)
                }
            }
            AuthMode.API_KEY -> {
                createApiKeyConnection(settings.getApiKey().trim())
            }
            AuthMode.NONE -> {
                return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
            }
        }

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(buildContent(userPrompt)))
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 200)
                }
            )
        }

        return try {
            connection.outputStream.use { outputStream ->
                outputStream.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseText = readResponse(connection)
            val parsed = parseCommand(responseText)
            if (parsed.isNullOrBlank()) {
                GeminiResult(false, context.getString(R.string.gemini_output_error))
            } else {
                GeminiResult(true, parsed)
            }
        } catch (ex: Exception) {
            GeminiResult(false, ex.message ?: context.getString(R.string.gemini_output_error))
        } finally {
            connection.disconnect()
        }
    }

    private fun createApiKeyConnection(apiKey: String): HttpURLConnection {
        val url = URI.create(
            "$BASE_URL?key=$apiKey"
        ).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }
    }

    private fun createOAuthConnection(accessToken: String): HttpURLConnection {
        val url = URI.create(BASE_URL).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }
    }

    private fun buildContent(userPrompt: String): JSONObject {
        val prompt = buildPrompt(userPrompt)
        val part = JSONObject().put("text", prompt)
        return JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(part))
        }
    }

    private fun buildPrompt(userPrompt: String): String {
        return """
You are a command generator for a remote Linux shell accessed over SSH.
Convert the user request into a single, safe shell command.
Prefer non-destructive commands. Do not use sudo unless explicitly requested.
Output only the command and nothing else. If clarification is needed, output:
CLARIFY: <short question>

User request: $userPrompt
""".trimIndent()
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        BufferedReader(InputStreamReader(stream)).use { reader ->
            val builder = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                builder.append(line)
                line = reader.readLine()
            }
            return builder.toString()
        }
    }

    private fun parseCommand(json: String): String? {
        val response = JSONObject(json)
        val candidates = response.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val content = first.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        val text = parts.optJSONObject(0)?.optString("text")
        return text?.trim()
    }

    enum class AuthMode {
        GOOGLE_ACCOUNT,
        API_KEY,
        NONE
    }

    companion object {
        private const val MODEL_ID = "gemini-1.5-flash"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:generateContent"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

data class GeminiResult(
    val success: Boolean,
    val message: String
)
