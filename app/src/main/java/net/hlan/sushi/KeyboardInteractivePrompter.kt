package net.hlan.sushi

/**
 * Asks the user to answer a `keyboard-interactive` round the stored password cannot.
 *
 * Implemented by the session's shared [com.jcraft.jsch.UserInfo] ([DialogUserInfo] in the app);
 * [KeyboardInteractiveUserInfo] finds it there. Called on the connecting thread, and blocks it
 * until the user answers, cancels, or the prompt times out.
 */
fun interface KeyboardInteractivePrompter {
    /**
     * One answer per [KeyboardInteractiveRequest.prompts] entry, in the same order, or null when
     * the user cancelled or did not answer in time — which declines the round.
     */
    fun promptKeyboardInteractive(request: KeyboardInteractiveRequest): List<String>?
}
