package net.hlan.sushi

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart

/**
 * On-device Gemini Nano client using the ML Kit GenAI Prompt API.
 *
 * Requires Android API 26+ and a supported device (Pixel 9/10 series and select others).
 * The model is managed by Android AICore — no API key or network required for inference.
 *
 * Always call [checkStatus] before [generateCommand]. Call [close] when the owning
 * component is destroyed.
 */
class GeminiNanoClient(private val context: Context) {

    private val model: GenerativeModel = Generation.getClient()

    /**
     * Returns the current availability of Gemini Nano on this device.
     * One of [FeatureStatus.AVAILABLE], [FeatureStatus.DOWNLOADABLE],
     * [FeatureStatus.DOWNLOADING], or [FeatureStatus.UNAVAILABLE].
     */
    suspend fun checkStatus(): Int {
        return try {
            model.checkStatus()
        } catch (e: Exception) {
            Log.w(TAG, "checkStatus failed: ${e.message}")
            FeatureStatus.UNAVAILABLE
        }
    }

    /**
     * Triggers a download of Gemini Nano via AICore and suspends until the download
     * completes or fails. Progress updates are delivered via [onProgress].
     *
     * Safe to call when status is [FeatureStatus.DOWNLOADABLE].
     */
    suspend fun download(
        onProgress: (Long) -> Unit = {},
        onComplete: () -> Unit = {},
        onFailed: (Exception) -> Unit = {}
    ) {
        try {
            model.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadProgress -> {
                        onProgress(status.totalBytesDownloaded)
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        Log.d(TAG, "Nano download complete")
                        onComplete()
                    }
                    is DownloadStatus.DownloadFailed -> {
                        Log.e(TAG, "Nano download failed: ${status.e.message}")
                        onFailed(status.e)
                    }
                    else -> {
                        Log.d(TAG, "Nano download status: $status")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nano download error: ${e.message}")
            onFailed(e)
        }
    }

    /**
     * Warms up Gemini Nano by loading it into memory. Optional but reduces first-inference
     * latency. Call from the main activity's onResume when status is AVAILABLE.
     */
    suspend fun warmup() {
        try {
            model.warmup()
            Log.d(TAG, "Nano warmup complete")
        } catch (e: Exception) {
            Log.w(TAG, "Nano warmup failed: ${e.message}")
        }
    }

    /**
     * Generates a shell command from [userPrompt] using on-device Gemini Nano.
     * Returns a [GeminiResult] with the same contract as [GeminiClient.generateCommand].
     *
     * Must be called from a coroutine (suspend function). Runs inference on the calling
     * coroutine context — ensure it is a background dispatcher.
     */
    suspend fun generateCommand(userPrompt: String): GeminiResult {
        return try {
            val requestBuilder = GenerateContentRequest.Builder(TextPart(buildPrompt(userPrompt)))
            requestBuilder.temperature = 0.2f
            requestBuilder.maxOutputTokens = 200
            val request = requestBuilder.build()

            val response: GenerateContentResponse = model.generateContent(request)
            val text = response.candidates
                .firstOrNull()
                ?.text
                ?.trim()

            if (text.isNullOrBlank()) {
                GeminiResult(false, context.getString(R.string.gemini_output_error))
            } else {
                GeminiResult(true, text)
            }
        } catch (e: GenAiException) {
            Log.e(TAG, "Nano inference failed: ${e.message}")
            GeminiResult(false, context.getString(R.string.gemini_output_error))
        } catch (e: Exception) {
            Log.e(TAG, "Nano generateCommand error: ${e.message}")
            GeminiResult(false, e.message ?: context.getString(R.string.gemini_output_error))
        }
    }

    /**
     * Releases the underlying [GenerativeModel]. Call from the activity's onDestroy.
     */
    fun close() {
        try {
            model.close()
        } catch (e: Exception) {
            Log.w(TAG, "Nano close error: ${e.message}")
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

    companion object {
        private const val TAG = "GeminiNanoClient"
    }
}
