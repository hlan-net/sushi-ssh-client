package net.hlan.sushi

import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How [KeyboardInteractiveUserInfo] answers a `keyboard-interactive` round. The rule that
 * matters most is the negative one: the stored password goes to exactly one kind of prompt, once,
 * and every other challenge is declined rather than answered with it.
 */
class KeyboardInteractiveUserInfoTest {

    private class RecordingUserInfo : UserInfo {
        var passphrasePrompts = 0
        var messages = mutableListOf<String>()

        override fun getPassphrase(): String = "delegate-passphrase"
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean {
            passphrasePrompts++
            return true
        }
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {
            messages += message.orEmpty()
        }
    }

    private fun respond(
        prompts: Array<String>,
        echo: BooleanArray,
        password: String? = "secret",
        alreadySent: Boolean = false
    ) = KeyboardInteractiveUserInfo.respond(prompts, echo, password, alreadySent)

    // --- what is answered ---

    @Test
    fun singleEchoOffPasswordPrompt_isAnsweredWithTheStoredPassword() {
        assertArrayEquals(
            arrayOf("secret"),
            respond(arrayOf("Password for larry@ergo: "), booleanArrayOf(false))
        )
    }

    /**
     * OpenSSH/PAM's usual prompts. JSch's own class answers these itself; the bounded copy passes
     * them here, so the wrapper answers them like any other password prompt.
     */
    @Test
    fun pamPasswordPrompts_areAnsweredWithTheStoredPassword() {
        assertArrayEquals(arrayOf("secret"), respond(arrayOf("Password: "), booleanArrayOf(false)))
        assertArrayEquals(
            arrayOf("secret"),
            respond(arrayOf("larry@ergo's password: "), booleanArrayOf(false))
        )
    }

    @Test
    fun passwordPromptMatchIsCaseInsensitive() {
        assertArrayEquals(
            arrayOf("secret"),
            respond(arrayOf("Enter PASSWORD "), booleanArrayOf(false))
        )
    }

    /** An empty round carries only a name or instruction; declining it would end the method. */
    @Test
    fun zeroPrompts_areAnsweredWithNoResponses() {
        val response = respond(emptyArray(), BooleanArray(0))
        assertEquals(0, response!!.size)
    }

    @Test
    fun zeroPrompts_areAnsweredEvenWithNoPassword() {
        assertEquals(0, respond(emptyArray(), BooleanArray(0), password = null)!!.size)
    }

    // --- what is declined ---

    /** An echo-on prompt is not asking for a secret, whatever its text says. */
    @Test
    fun echoOnPrompt_isNeverAnsweredWithThePassword() {
        assertNull(respond(arrayOf("Password hint: "), booleanArrayOf(true)))
    }

    @Test
    fun multiplePrompts_areDeclined() {
        assertNull(
            respond(arrayOf("Password: ", "Verification code: "), booleanArrayOf(false, false))
        )
    }

    /** A one-time code is not the password; sending it would just spend an attempt. */
    @Test
    fun nonPasswordEchoOffPrompt_isDeclined() {
        assertNull(respond(arrayOf("Verification code: "), booleanArrayOf(false)))
    }

    @Test
    fun secondPasswordPrompt_isDeclined() {
        assertNull(
            respond(arrayOf("Password for larry@ergo: "), booleanArrayOf(false), alreadySent = true)
        )
    }

    @Test
    fun passwordPromptWithoutStoredPassword_isDeclined() {
        assertNull(respond(arrayOf("Password for larry@ergo: "), booleanArrayOf(false), password = null))
        assertNull(respond(arrayOf("Password for larry@ergo: "), booleanArrayOf(false), password = ""))
    }

    /** A malformed round where the echo flags do not match the prompts is not guessed at. */
    @Test
    fun missingEchoFlag_isDeclined() {
        assertNull(respond(arrayOf("Password for larry@ergo: "), BooleanArray(0)))
    }

    // --- the wrapper itself ---

    /** `UserAuthKeyboardInteractive.start` returns false for any UserInfo that is not one. */
    @Test
    fun wrapperIsAKeyboardInteractiveUserInfo() {
        assertTrue(KeyboardInteractiveUserInfo(RecordingUserInfo(), "secret") is UIKeyboardInteractive)
    }

    @Test
    fun wrapperSendsThePasswordOnlyOncePerSession() {
        val userInfo = KeyboardInteractiveUserInfo(RecordingUserInfo(), "secret")
        val prompt = arrayOf("Password for larry@ergo: ")
        val echo = booleanArrayOf(false)

        assertArrayEquals(
            arrayOf("secret"),
            userInfo.promptKeyboardInteractive("larry@ergo", "", "", prompt, echo)
        )
        assertNull(userInfo.promptKeyboardInteractive("larry@ergo", "", "", prompt, echo))
    }

    /**
     * Once the password has gone out, a later password prompt is not answered again, whatever
     * its wording. This is the rule JSch's own automatic `Password:` answer could bypass.
     */
    @Test
    fun differentlyWordedSecondPasswordPrompt_isDeclined() {
        val userInfo = KeyboardInteractiveUserInfo(RecordingUserInfo(), "secret")

        assertArrayEquals(
            arrayOf("secret"),
            userInfo.promptKeyboardInteractive("larry@ergo", "", "", arrayOf("Password: "), booleanArrayOf(false))
        )
        assertNull(
            userInfo.promptKeyboardInteractive(
                "larry@ergo", "", "", arrayOf("Password for larry@ergo: "), booleanArrayOf(false)
            )
        )
    }

    /**
     * A declined round sends nothing, even when a prompt in it says `password:`, so it must not
     * count as the password having gone out.
     */
    @Test
    fun declinedRounds_doNotCountAsSent() {
        val userInfo = KeyboardInteractiveUserInfo(RecordingUserInfo(), "secret")

        userInfo.promptKeyboardInteractive(
            "larry@ergo", "", "", arrayOf("Password: ", "Verification code: "), booleanArrayOf(false, false)
        )
        userInfo.promptKeyboardInteractive("larry@ergo", "", "", arrayOf("Password: "), booleanArrayOf(true))

        assertArrayEquals(
            arrayOf("secret"),
            userInfo.promptKeyboardInteractive(
                "larry@ergo", "", "", arrayOf("Password for larry@ergo: "), booleanArrayOf(false)
            )
        )
    }

    /** An empty round in between must not count as having sent the password. */
    @Test
    fun emptyRoundDoesNotSpendThePassword() {
        val userInfo = KeyboardInteractiveUserInfo(RecordingUserInfo(), "secret")

        userInfo.promptKeyboardInteractive("larry@ergo", "", "Welcome", emptyArray(), BooleanArray(0))

        assertArrayEquals(
            arrayOf("secret"),
            userInfo.promptKeyboardInteractive(
                "larry@ergo", "", "", arrayOf("Password for larry@ergo: "), booleanArrayOf(false)
            )
        )
    }

    @Test
    fun nullPromptArrays_areTreatedAsAnEmptyRound() {
        val userInfo = KeyboardInteractiveUserInfo(RecordingUserInfo(), "secret")
        assertEquals(0, userInfo.promptKeyboardInteractive("larry@ergo", null, null, null, null)!!.size)
    }

    /** Host-key trust and key passphrases still reach the dialog-backed UserInfo. */
    @Test
    fun otherCallbacksReachTheDelegate() {
        val delegate = RecordingUserInfo()
        val userInfo = KeyboardInteractiveUserInfo(delegate, "secret")

        assertTrue(userInfo.promptPassphrase("Passphrase for key"))
        assertEquals("delegate-passphrase", userInfo.passphrase)
        userInfo.showMessage("banner")

        assertEquals(1, delegate.passphrasePrompts)
        assertEquals(listOf("banner"), delegate.messages)
    }
}
