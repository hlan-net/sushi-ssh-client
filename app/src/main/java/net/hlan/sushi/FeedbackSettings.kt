package net.hlan.sushi

import android.content.Context

class FeedbackSettings(context: Context) {
    private val prefs = SecurePrefs.get(context)

    fun getGitHubToken(): String = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""

    fun setGitHubToken(token: String) {
        prefs.edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

    fun getGitHubUsername(): String = prefs.getString(KEY_GITHUB_USERNAME, "") ?: ""

    fun setGitHubUsername(username: String) {
        prefs.edit().putString(KEY_GITHUB_USERNAME, username).apply()
    }

    fun isConfigured(): Boolean = getGitHubToken().isNotBlank()

    fun clear() {
        prefs.edit().remove(KEY_GITHUB_TOKEN).remove(KEY_GITHUB_USERNAME).apply()
    }

    companion object {
        private const val KEY_GITHUB_TOKEN = "feedback_github_token"
        private const val KEY_GITHUB_USERNAME = "feedback_github_username"
    }
}
