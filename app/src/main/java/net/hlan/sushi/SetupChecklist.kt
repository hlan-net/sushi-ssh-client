package net.hlan.sushi

data class SetupChecklistState(
    val hasSshHost: Boolean,
    val hasSshKey: Boolean,
    val hasGeminiKey: Boolean,
    val hasDriveAuth: Boolean
) {
    val requiredComplete: Boolean get() = hasSshHost && hasSshKey
}

object SetupChecklist {
    fun evaluate(
        sshSettings: SshSettings,
        geminiSettings: GeminiSettings,
        driveAuthManager: DriveAuthManager
    ): SetupChecklistState = SetupChecklistState(
        hasSshHost = sshSettings.getHosts().any { it.kind == HostKind.SSH },
        hasSshKey = sshSettings.getPublicKey() != null,
        hasGeminiKey = geminiSettings.isEnabled() &&
            (geminiSettings.getApiKey().isNotBlank() || driveAuthManager.getSignedInAccount() != null),
        hasDriveAuth = driveAuthManager.getSignedInAccount() != null
    )
}
