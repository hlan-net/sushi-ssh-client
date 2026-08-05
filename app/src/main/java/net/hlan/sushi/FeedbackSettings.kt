package net.hlan.sushi

import android.content.Context

class FeedbackSettings(context: Context) {
    private val prefs = SecurePrefs.get(context)

    fun getGitHubToken(): String = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""

    fun setGitHubToken(token: String) {
        prefs.edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

    fun isConfigured(): Boolean = getGitHubToken().isNotBlank()

    companion object {
        private const val KEY_GITHUB_TOKEN = "feedback_github_token"
    }
}
