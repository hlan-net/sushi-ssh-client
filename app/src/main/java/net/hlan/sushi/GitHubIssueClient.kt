package net.hlan.sushi

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

/**
 * Creates GitHub issues from in-app feedback. Uses a fine-grained personal
 * access token (Issues: Read+Write on the app repo only) stored in SecurePrefs.
 */
class GitHubIssueClient(
    private val context: Context,
    private val settings: FeedbackSettings
) {

    fun createIssue(feedback: String, includeDeviceInfo: Boolean): FeedbackResult {
        val token = settings.getGitHubToken().trim()
        if (token.isEmpty()) {
            return FeedbackResult(false, context.getString(R.string.feedback_missing_token))
        }

        val title = feedback.lineSequence().first().take(TITLE_MAX_LENGTH).ifBlank {
            context.getString(R.string.feedback_default_title)
        }
        val body = buildString {
            append(feedback)
            if (includeDeviceInfo) {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                append("\n\n---\n")
                append("- App: ${packageInfo.versionName} (${packageInfo.longVersionCode})\n")
                append("- Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
        }

        val requestBody = JSONObject().apply {
            put("title", title)
            put("body", body)
            put("labels", JSONArray().put(LABEL))
        }

        val connection = URI(ISSUES_URL).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        return try {
            connection.outputStream.use { outputStream ->
                outputStream.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val issueUrl = JSONObject(response).optString("html_url")
                FeedbackResult(true, issueUrl)
            } else {
                val error = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream)).use { it.readText() }
                }.orEmpty()
                val message = runCatching { JSONObject(error).optString("message") }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { context.getString(R.string.feedback_send_failed) }
                FeedbackResult(false, "${connection.responseCode}: $message")
            }
        } catch (ex: Exception) {
            FeedbackResult(false, ex.message ?: context.getString(R.string.feedback_send_failed))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ISSUES_URL = "https://api.github.com/repos/hlan-net/sushi-ssh-client/issues"
        private const val LABEL = "from-app"
        private const val TITLE_MAX_LENGTH = 80
    }
}

data class FeedbackResult(
    val success: Boolean,
    val message: String
)
