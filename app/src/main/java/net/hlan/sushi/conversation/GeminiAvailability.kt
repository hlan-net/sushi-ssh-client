package net.hlan.sushi.conversation

/**
 * Whether any Gemini backend can answer a request right now — on-device Nano or the cloud —
 * which is what gates voice input (#231). Cloud credentials alone used to decide, so a user with
 * only Nano was sent to Settings for an API key the request would never have used.
 */
enum class GeminiAvailability {
    /** Nano is preferred and available, or the cloud has an API key or Google account. */
    READY,

    /** Gemini is switched off in Settings. */
    DISABLED,

    /** Gemini is on, but neither Nano nor cloud credentials can answer. */
    MISSING_CREDENTIALS;

    companion object {
        /**
         * Nano counts only when it is both preferred and available (`FeatureStatus.AVAILABLE`) — the same
         * test [AppConversationEnvironment] routes requests by, so a READY here is a request that
         * really is answered. A downloadable Nano is not ready yet.
         */
        fun resolve(
            enabled: Boolean,
            hasCloudCredentials: Boolean,
            nanoPreferredAndAvailable: Boolean
        ): GeminiAvailability = when {
            !enabled -> DISABLED
            hasCloudCredentials || nanoPreferredAndAvailable -> READY
            else -> MISSING_CREDENTIALS
        }
    }
}
