package net.hlan.sushi

import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.util.Locale

/**
 * Lets a session answer `keyboard-interactive` — with the password stored for *that* host where
 * that is the answer, and by asking the user otherwise — and forwards every other [UserInfo]
 * callback to [delegate] unchanged.
 *
 * One is built per session in [SshClient], because the target and the jump host share a single
 * [UserInfo] but not a password — binding the password to the shared instance would answer the
 * bastion with the target's.
 *
 * Implementing the interface is what makes the method usable at all: JSch's
 * `UserAuthKeyboardInteractive` returns false before sending a request when the session's
 * `UserInfo` is not a [UIKeyboardInteractive]. The session runs
 * `com.jcraft.jsch.BoundedUserAuthKeyboardInteractive` instead, which passes every round here —
 * JSch's own class would answer a `Password:` prompt itself, behind this class's back — so this is
 * the one place that decides what is sent. See [respond] for the stored-password answer; every
 * other non-empty round goes to the [KeyboardInteractivePrompter] the delegate implements, if it
 * does, and is declined if it does not.
 */
internal class KeyboardInteractiveUserInfo(
    private val delegate: UserInfo,
    private val password: String?,
    /** `host:port` of this hop, for the dialog. */
    private val hostLabel: String = "",
    private val isJumpHost: Boolean = false
) : UserInfo by delegate, UIKeyboardInteractive {

    /** What the user did with the rounds put to them, for classifying a failed connect. */
    enum class UserOutcome {
        /** No round reached the user. */
        NONE,
        /** The user answered the last round put to them. */
        ANSWERED,
        /** The user cancelled the last round put to them, or let it time out. */
        CANCELLED
    }

    private var passwordSent = false

    @Volatile
    var userOutcome: UserOutcome = UserOutcome.NONE
        private set

    private val prompter: KeyboardInteractivePrompter? = delegate as? KeyboardInteractivePrompter

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<String>?,
        echo: BooleanArray?
    ): Array<String>? {
        val prompts = prompt ?: emptyArray()
        val echoes = echo ?: BooleanArray(0)
        val automatic = respond(prompts, echoes, password, passwordSent)
        if (automatic != null) {
            if (automatic.isNotEmpty()) {
                passwordSent = true
            }
            return automatic
        }
        if (passwordSent && isSinglePasswordPrompt(prompts, echoes)) {
            // The stored password was just rejected. Declining keeps the failure an
            // AUTH_PASSWORD one, whose banner says the credentials are wrong and offers to edit
            // them; asking would let a cancel hide that the saved password is wrong.
            return null
        }
        val asker = prompter ?: return null
        val request = KeyboardInteractiveRequest.from(hostLabel, isJumpHost, name, instruction, prompts, echoes)
        val answers = asker.promptKeyboardInteractive(request)
        // A short or long answer list would make JSch send a malformed response; treat it as a
        // cancel rather than guess which prompt each answer was for.
        if (answers == null || answers.size != prompts.size) {
            userOutcome = UserOutcome.CANCELLED
            return null
        }
        userOutcome = UserOutcome.ANSWERED
        return answers.toTypedArray()
    }

    companion object {
        /**
         * The answer to one `SSH_MSG_USERAUTH_INFO_REQUEST` that needs no user, or null when it
         * does — the round then goes to the [KeyboardInteractivePrompter], or is declined.
         *
         * - **No prompts** — some servers send an empty round (only a name or an instruction).
         *   It is answered with no responses rather than shown or declined, which would end the
         *   method.
         * - **One echo-off prompt that asks for a password** — answered with the stored password,
         *   once per session. A second password prompt means the first answer was rejected, and
         *   repeating a known-wrong password only spends the server's `MaxAuthTries`; it is
         *   declined, not put to the user, so the failure still says the password is wrong.
         *   With no stored password, the prompt goes to the user.
         * - **Anything else** — an echo-on prompt, several prompts, or a one-time code — is never
         *   answered with the password. The saved password is not prefilled in the dialog either,
         *   even for a `Password:` prompt among several (#191).
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
            return when {
                password.isNullOrEmpty() || passwordAlreadySent -> null
                isSinglePasswordPrompt(prompts, echo) -> arrayOf(password)
                else -> null
            }
        }

        /** One echo-off prompt whose text asks for a password. */
        internal fun isSinglePasswordPrompt(prompts: Array<String>, echo: BooleanArray): Boolean {
            val onlyPrompt = prompts.singleOrNull() ?: return false
            return echo.singleOrNull() == false && "password" in onlyPrompt.lowercase(Locale.ROOT)
        }
    }
}
