package net.hlan.sushi

data class GitHubDeviceFlowState(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMs: Long,
    val intervalSeconds: Int
) {
    fun toDeviceCode(nowMs: Long = System.currentTimeMillis()): GitHubAuthManager.DeviceCode? {
        val remainingMs = expiresAtMs - nowMs
        if (remainingMs <= 0L) {
            return null
        }
        val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
        return GitHubAuthManager.DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            expiresIn = remainingSeconds,
            interval = intervalSeconds
        )
    }

    companion object {
        fun fromDeviceCode(
            deviceCode: GitHubAuthManager.DeviceCode,
            nowMs: Long = System.currentTimeMillis()
        ) = GitHubDeviceFlowState(
            deviceCode = deviceCode.deviceCode,
            userCode = deviceCode.userCode,
            verificationUri = deviceCode.verificationUri,
            expiresAtMs = nowMs + deviceCode.expiresIn * 1000L,
            intervalSeconds = deviceCode.interval
        )
    }
}
