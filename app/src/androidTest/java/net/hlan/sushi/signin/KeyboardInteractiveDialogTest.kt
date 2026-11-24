package net.hlan.sushi.signin

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.hlan.sushi.KeyboardInteractiveRequest
import net.hlan.sushi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [KeyboardInteractiveDialog] (#191; Figma card `node-id=131-2`), feeding
 * it a [KeyboardInteractiveRequest] directly. Which rounds reach the dialog, and what the answers
 * do on the wire, is covered on the JVM by `KeyboardInteractiveUserInfoTest` and
 * `SshClientKeyboardInteractiveTest`; this covers what only composition can: the texts shown, a
 * hidden versus a visible field, and the answers coming back in the server's order.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardInteractiveDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val oneTimeCode = KeyboardInteractiveRequest(
        hostLabel = "otp.hlan.net:22",
        isJumpHost = false,
        name = "",
        instruction = "",
        prompts = listOf(KeyboardInteractiveRequest.Prompt("Verification code:", echo = false))
    )

    private val duo = KeyboardInteractiveRequest(
        hostLabel = "bastion.hlan.net:22",
        isJumpHost = true,
        name = "",
        instruction = "Duo two-factor login for larry",
        prompts = listOf(
            KeyboardInteractiveRequest.Prompt("Password:", echo = false),
            KeyboardInteractiveRequest.Prompt("Passcode or option (1-3):", echo = true)
        )
    )

    private var submitted: List<String>? = null
    private var cancelled = false

    private fun show(request: KeyboardInteractiveRequest) {
        composeRule.setContent {
            KeyboardInteractiveDialog(
                request = request,
                onSubmit = { submitted = it },
                onCancel = { cancelled = true }
            )
        }
    }

    @Test
    fun showsTitleHostAndTheServersPrompt() {
        show(oneTimeCode)

        composeRule.onNodeWithText(context.getString(R.string.keyboard_interactive_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.HOST).assertTextContains("otp.hlan.net:22")
        composeRule.onNodeWithText("Verification code:").assertIsDisplayed()
        composeRule.onAllNodesWithTag(KeyboardInteractiveDialogTestTags.INSTRUCTION).assertCountEquals(0)
    }

    @Test
    fun namesTheJumpHostAndShowsTheInstruction() {
        show(duo)

        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.HOST)
            .assertTextContains(context.getString(R.string.keyboard_interactive_jump_host, "bastion.hlan.net:22"))
        composeRule.onNodeWithText("Duo two-factor login for larry").assertIsDisplayed()
    }

    @Test
    fun echoOffFieldIsHidden_echoOnFieldIsShown() {
        show(duo)

        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.field(0)).assert(isPassword(true))
        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.field(1)).assert(isPassword(false))
    }

    /** The saved password is never prefilled: every field starts empty. */
    @Test
    fun fieldsStartEmpty() {
        show(duo)

        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.CONTINUE).performClick()

        assertEquals(listOf("", ""), submitted)
    }

    @Test
    fun continueReturnsTheAnswersInTheServersOrder() {
        show(duo)

        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.field(0)).performTextInput("hunter2")
        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.field(1)).performTextInput("1")
        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.CONTINUE).performClick()

        assertEquals(listOf("hunter2", "1"), submitted)
        assertTrue(!cancelled)
    }

    @Test
    fun cancelReturnsNoAnswers() {
        show(oneTimeCode)

        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.field(0)).performTextInput("123")
        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTags.CANCEL).performClick()

        assertTrue(cancelled)
        assertNull(submitted)
    }

    private fun isPassword(expected: Boolean) = SemanticsMatcher("isPassword == $expected") { node ->
        node.config.contains(SemanticsProperties.Password) == expected
    }
}
