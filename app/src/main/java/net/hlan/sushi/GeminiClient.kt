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

    /**
     * Generate a conversational response using SUSHI.md context from target system.
     * The system will respond AS the target system (first person).
     * 
     * @param userMessage The user's message/query
     * @param sushiMdContext The SUSHI.md content from the target system
     * @param conversationHistory Recent conversation turns for context
     * @return GeminiResult with the system's response (may include EXECUTE: directive)
     */
    fun generateConversationalResponse(
        userMessage: String,
        sushiMdContext: String,
        conversationHistory: List<ConversationTurn> = emptyList()
    ): GeminiResult {
        val authMode = getAuthMode()
        if (authMode == AuthMode.NONE) {
            return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
        }

        val modelId = settings.getCloudModel()
        val baseUrl = BASE_URL_TEMPLATE.format(modelId)

        val connection = when (authMode) {
            AuthMode.GOOGLE_ACCOUNT -> {
                val token = authManager?.getGeminiAccessToken()
                if (token == null) {
                    val apiKey = settings.getApiKey().trim()
                    if (apiKey.isEmpty()) {
                        return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
                    }
                    createApiKeyConnection(apiKey, baseUrl)
                } else {
                    createOAuthConnection(token, baseUrl)
                }
            }
            AuthMode.API_KEY -> {
                createApiKeyConnection(settings.getApiKey().trim(), baseUrl)
            }
            AuthMode.NONE -> {
                return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
            }
        }

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(buildConversationalContent(userMessage, sushiMdContext, conversationHistory)))
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 500)
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

    fun generateCommand(userPrompt: String): GeminiResult {
        val authMode = getAuthMode()
        if (authMode == AuthMode.NONE) {
            return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
        }

        val modelId = settings.getCloudModel()
        val baseUrl = BASE_URL_TEMPLATE.format(modelId)

        val connection = when (authMode) {
            AuthMode.GOOGLE_ACCOUNT -> {
                val token = authManager?.getGeminiAccessToken()
                if (token == null) {
                    // Token retrieval failed — fall back to API key if available
                    val apiKey = settings.getApiKey().trim()
                    if (apiKey.isEmpty()) {
                        return GeminiResult(false, context.getString(R.string.gemini_missing_key_message))
                    }
                    createApiKeyConnection(apiKey, baseUrl)
                } else {
                    createOAuthConnection(token, baseUrl)
                }
            }
            AuthMode.API_KEY -> {
                createApiKeyConnection(settings.getApiKey().trim(), baseUrl)
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

    private fun createApiKeyConnection(apiKey: String, baseUrl: String): HttpURLConnection {
        val url = URI.create("$baseUrl?key=$apiKey").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }
    }

    private fun createOAuthConnection(accessToken: String, baseUrl: String): HttpURLConnection {
        val url = URI.create(baseUrl).toURL()
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

    private fun buildConversationalContent(
        userMessage: String,
        sushiMdContext: String,
        conversationHistory: List<ConversationTurn>
    ): JSONObject {
        val prompt = buildConversationalPrompt(userMessage, sushiMdContext, conversationHistory)
        val part = JSONObject().put("text", prompt)
        return JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(part))
        }
    }

    private fun buildConversationalPrompt(
        userMessage: String,
        sushiMdContext: String,
        conversationHistory: List<ConversationTurn>
    ): String {
        val historyText = if (conversationHistory.isNotEmpty()) {
            val recent = conversationHistory.takeLast(5)
            recent.joinToString("\n\n") { turn ->
                "User: ${turn.userMessage}\nSystem: ${turn.systemResponse}"
            }
        } else {
            "(No conversation history yet)"
        }

        return """
$sushiMdContext

---

## Conversation Context

Recent conversation:
$historyText

---

## Current User Message

User: $userMessage

---

## Response Instructions

Respond naturally as this computer system in first person.

If you need to execute a command:
1. Explain what you're doing in natural language
2. On a new line, write: EXECUTE: <command>
3. The command will be run and you'll see the result
4. Then provide a natural language interpretation of the result

Example response format:
"Let me check my current temperature.

EXECUTE: vcgencmd measure_temp

(After seeing result: temp=52.0'C)

I am running at 52 degrees Celsius. Operating within normal parameters."

If the user's request is unclear, ask a clarifying question naturally.

Your response:
        """.trimIndent()
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
        const val MODEL_PRO = "gemini-2.5-pro"
        const val MODEL_FLASH = "gemini-1.5-flash"

        private const val BASE_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

data class GeminiResult(
    val success: Boolean,
    val message: String
)
