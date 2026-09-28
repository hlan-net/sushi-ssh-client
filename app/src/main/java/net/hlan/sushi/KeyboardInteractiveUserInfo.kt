package net.hlan.sushi

import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.util.Locale

/**
 * Lets a session answer `keyboard-interactive` with the password stored for *that* host, and
 * forwards every other [UserInfo] callback to [delegate] unchanged.
 *
 * One is built per session in [SshClient], because the target and the jump host share a single
 * [UserInfo] but not a password — binding the password to the shared instance would answer the
 * bastion with the target's.
 *
 * Implementing the interface is what makes the method usable at all: `UserAuthKeyboardInteractive`
 * returns false before sending a request when the session's `UserInfo` is not a
 * [UIKeyboardInteractive]. Once it is, JSch answers a lone echo-off prompt containing
 * `password:` from `session.password` on its own and only calls [promptKeyboardInteractive]
 * for everything else — see [respond] for how that is answered.
 */
internal class KeyboardInteractiveUserInfo(
    private val delegate: UserInfo,
    private val password: String?
) : UserInfo by delegate, UIKeyboardInteractive {

    private var passwordSent = false

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<String>?,
        echo: BooleanArray?
    ): Array<String>? {
        val prompts = prompt ?: emptyArray()
        // JSch's own answers never reach this method, so a `password:` prompt showing up here is
        // the only sign that JSch already sent the password earlier in this session. Recording
        // it keeps a later, differently worded password prompt from being answered again.
        if (prompts.any(::isAnsweredByJsch)) {
            passwordSent = true
        }
        val response = respond(prompts, echo ?: BooleanArray(0), password, passwordSent)
        if (response != null && response.isNotEmpty()) {
            passwordSent = true
        }
        return response
    }

    companion object {
        /**
         * The answer to one `SSH_MSG_USERAUTH_INFO_REQUEST`, or null to decline it.
         *
         * - **No prompts** — some servers send an empty round (only a name or an instruction).
         *   It is answered with no responses rather than declined, which would end the method.
         * - **One echo-off prompt that asks for a password** — answered with the stored password,
         *   once per session. A second password prompt means the first answer was rejected, and
         *   repeating a known-wrong password only spends the server's `MaxAuthTries`.
         * - **A prompt containing `password:`** — declined: JSch answers that one itself before
         *   calling here, so seeing it means JSch's answer was already rejected.
         * - **Anything else** — an echo-on prompt, several prompts, or a one-time code — is
         *   declined, never answered with the password. JSch then moves on to the next method.
         *
         * Declining is deliberate rather than a gap: prompting the user for these needs a dialog,
         * which is a UI change and goes through a Figma proposal first (#191).
         */
        internal fun respond(
            prompts: Array<String>,
            echo: BooleanArray,
            password: String?,
            passwordAlreadySent: Boolean
        ): Array<String>? {
            if (prompts.isEmpty()) {
                return emptyArray()
            }
            val onlyPrompt = prompts.singleOrNull() ?: return null
            val echoOff = echo.singleOrNull() == false
            return when {
                password.isNullOrEmpty() || passwordAlreadySent || !echoOff -> null
                isAnsweredByJsch(onlyPrompt) -> null
                "password" in onlyPrompt.lowercase(Locale.ROOT) -> arrayOf(password)
                else -> null
            }
        }

        /**
         * The test `UserAuthKeyboardInteractive` applies before answering from
         * `session.password` itself (it also requires a single echo-off prompt).
         */
        private fun isAnsweredByJsch(prompt: String): Boolean =
            "password:" in prompt.lowercase(Locale.ROOT)
    }
}
