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

    fun getPendingGitHubDeviceFlow(): GitHubDeviceFlowState? {
        val deviceCode = prefs.getString(KEY_GITHUB_DEVICE_CODE, "").orEmpty()
        val userCode = prefs.getString(KEY_GITHUB_USER_CODE, "").orEmpty()
        val verificationUri = prefs.getString(KEY_GITHUB_VERIFICATION_URI, "").orEmpty()
        val expiresAtMs = prefs.getLong(KEY_GITHUB_EXPIRES_AT_MS, 0L)
        val intervalSeconds = prefs.getInt(KEY_GITHUB_INTERVAL_SECONDS, 0)
        if (
            deviceCode.isBlank() ||
            userCode.isBlank() ||
            verificationUri.isBlank() ||
            expiresAtMs <= 0L ||
            intervalSeconds <= 0
        ) {
            return null
        }
        return GitHubDeviceFlowState(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtMs = expiresAtMs,
            intervalSeconds = intervalSeconds
        )
    }

    fun setPendingGitHubDeviceFlow(flowState: GitHubDeviceFlowState) {
        prefs.edit()
            .putString(KEY_GITHUB_DEVICE_CODE, flowState.deviceCode)
            .putString(KEY_GITHUB_USER_CODE, flowState.userCode)
            .putString(KEY_GITHUB_VERIFICATION_URI, flowState.verificationUri)
            .putLong(KEY_GITHUB_EXPIRES_AT_MS, flowState.expiresAtMs)
            .putInt(KEY_GITHUB_INTERVAL_SECONDS, flowState.intervalSeconds)
            .apply()
    }

    fun clearPendingGitHubDeviceFlow() {
        prefs.edit()
            .remove(KEY_GITHUB_DEVICE_CODE)
            .remove(KEY_GITHUB_USER_CODE)
            .remove(KEY_GITHUB_VERIFICATION_URI)
            .remove(KEY_GITHUB_EXPIRES_AT_MS)
            .remove(KEY_GITHUB_INTERVAL_SECONDS)
            .apply()
    }

    fun isConfigured(): Boolean = getGitHubToken().isNotBlank()

    fun clear() {
        prefs.edit()
            .remove(KEY_GITHUB_TOKEN)
            .remove(KEY_GITHUB_USERNAME)
            .remove(KEY_GITHUB_DEVICE_CODE)
            .remove(KEY_GITHUB_USER_CODE)
            .remove(KEY_GITHUB_VERIFICATION_URI)
            .remove(KEY_GITHUB_EXPIRES_AT_MS)
            .remove(KEY_GITHUB_INTERVAL_SECONDS)
            .apply()
    }

    companion object {
        private const val KEY_GITHUB_TOKEN = "feedback_github_token"
        private const val KEY_GITHUB_USERNAME = "feedback_github_username"
        private const val KEY_GITHUB_DEVICE_CODE = "feedback_github_device_code"
        private const val KEY_GITHUB_USER_CODE = "feedback_github_user_code"
        private const val KEY_GITHUB_VERIFICATION_URI = "feedback_github_verification_uri"
        private const val KEY_GITHUB_EXPIRES_AT_MS = "feedback_github_expires_at_ms"
        private const val KEY_GITHUB_INTERVAL_SECONDS = "feedback_github_interval_seconds"
    }
}
