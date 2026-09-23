package net.hlan.sushi.conversation

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.hlan.sushi.ConversationResult
import net.hlan.sushi.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [ConversationScreen] (ROADMAP.md v0.9.0; Figma card `node-id=103-2`),
 * feeding it [ConversationUiState] directly rather than driving a live [ConversationViewModel] —
 * the same "state in, callbacks out" boundary [ConversationViewModelTest] already covers on the
 * JVM side, tested here only for what a JVM test cannot reach: composition and clicks.
 */
@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: ConversationUiState = ConversationUiState(),
        availabilityStatus: String = "",
        onBack: () -> Unit = {},
        onSend: (String) -> Unit = {},
        onVoice: () -> Unit = {},
        onSettings: () -> Unit = {},
        onHistory: () -> Unit = {},
        onCopy: () -> Unit = {},
        onRawModeChange: (Boolean) -> Unit = {},
        onAutoTroubleshootChange: (Boolean) -> Unit = {},
        onConfirmPending: () -> Unit = {},
        onDeclinePending: () -> Unit = {}
    ) {
        composeRule.setContent {
            ConversationScreen(
                state = state,
                availabilityStatus = availabilityStatus,
                onBack = onBack,
                onSend = onSend,
                onVoice = onVoice,
                onSettings = onSettings,
                onHistory = onHistory,
                onCopy = onCopy,
                onRawModeChange = onRawModeChange,
                onAutoTroubleshootChange = onAutoTroubleshootChange,
                onConfirmPending = onConfirmPending,
                onDeclinePending = onDeclinePending
            )
        }
    }

    @Test
    fun topBar_showsHostLabelAndAvailabilityWhenDisconnected() {
        setScreen(
            state = ConversationUiState(hostLabel = "ekho", status = ConversationStatus.Disconnected),
            availabilityStatus = "Gemini is ready for voice input."
        )

        composeRule.onNodeWithText("ekho").assertExists()
        composeRule.onNodeWithText("Gemini is ready for voice input.").assertExists()
    }

    @Test
    fun topBar_fallsBackToDialogTitleWhenNoHost() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        setScreen(state = ConversationUiState(hostLabel = null))

        composeRule.onNodeWithText(context.getString(R.string.gemini_dialog_title)).assertExists()
    }

    @Test
    fun backButton_invokesOnBack() {
        var backClicked = false
        setScreen(onBack = { backClicked = true })

        composeRule.onNodeWithTag(ConversationScreenTestTags.BACK_BUTTON).performClick()

        assert(backClicked)
    }

    @Test
    fun rawModeChip_clickTogglesRawMode() {
        var newValue: Boolean? = null
        setScreen(
            state = ConversationUiState(isRawMode = false),
            onRawModeChange = { newValue = it }
        )

        composeRule.onNodeWithTag(ConversationScreenTestTags.RAW_MODE_CHIP).performClick()

        assert(newValue == true)
    }

    @Test
    fun autoTroubleshootChip_disabledWhenRawModeIsOn() {
        setScreen(state = ConversationUiState(isRawMode = true))

        composeRule.onNodeWithTag(ConversationScreenTestTags.AUTO_TROUBLESHOOT_CHIP).assertIsNotEnabled()
    }

    @Test
    fun inputBar_sendButtonSubmitsTypedTextAndClearsField() {
        var sent: String? = null
        setScreen(onSend = { sent = it })

        composeRule.onNodeWithTag(ConversationScreenTestTags.INPUT_FIELD).performTextInput("is nginx up?")
        composeRule.onNodeWithTag(ConversationScreenTestTags.SEND_BUTTON).performClick()

        assert(sent == "is nginx up?")
        composeRule.onNode(hasText("is nginx up?")).assertDoesNotExist()
    }

    @Test
    fun inputBar_sendButtonDisabledWhenFieldIsEmpty() {
        setScreen()

        composeRule.onNodeWithTag(ConversationScreenTestTags.SEND_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun transcript_rendersTurnPromptAndResponse() {
        setScreen(
            state = ConversationUiState(
                transcript = listOf(TranscriptItem.Turn(id = 1, prompt = "why is nginx down?", response = "checking now"))
            )
        )

        composeRule.onNodeWithText("why is nginx down?").assertExists()
        composeRule.onNodeWithText("checking now").assertExists()
    }

    @Test
    fun confirmCard_runInvokesOnConfirmAndSkipInvokesOnDecline() {
        var confirmed = false
        val pending = PendingConfirmation(
            command = "sudo systemctl restart nginx",
            userMessage = "restart nginx",
            kind = PendingConfirmation.Kind.AI,
            turnId = 1,
            result = ConversationResult(
                success = true,
                systemResponse = "Restarting.",
                userMessage = "restart nginx",
                needsConfirmation = true,
                commandToConfirm = "sudo systemctl restart nginx"
            )
        )
        setScreen(
            state = ConversationUiState(pendingConfirmation = pending),
            onConfirmPending = { confirmed = true }
        )

        composeRule.onNodeWithText("sudo systemctl restart nginx").assertExists()
        composeRule.onNodeWithTag(ConversationScreenTestTags.CONFIRM_RUN_BUTTON).performClick()

        assert(confirmed)
    }

    @Test
    fun moreActionsMenu_copyItemHiddenWithoutOutput() {
        setScreen(state = ConversationUiState(lastOutput = ""))

        composeRule.onNodeWithTag(ConversationScreenTestTags.MORE_ACTIONS_BUTTON).performClick()

        composeRule.onNodeWithTag(ConversationScreenTestTags.COPY_MENU_ITEM).assertDoesNotExist()
    }

    @Test
    fun moreActionsMenu_settingsItemInvokesOnSettings() {
        var settingsOpened = false
        setScreen(onSettings = { settingsOpened = true })

        composeRule.onNodeWithTag(ConversationScreenTestTags.MORE_ACTIONS_BUTTON).performClick()
        composeRule.onNodeWithTag(ConversationScreenTestTags.SETTINGS_MENU_ITEM).performClick()

        assert(settingsOpened)
    }
}
