package net.hlan.sushi.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import net.hlan.sushi.R

/** Compose test tags for [ConversationScreen]'s interactive elements. */
object ConversationScreenTestTags {
    const val BACK_BUTTON = "conversation_back_button"
    const val MORE_ACTIONS_BUTTON = "conversation_more_actions_button"
    const val SETTINGS_MENU_ITEM = "conversation_settings_menu_item"
    const val HISTORY_MENU_ITEM = "conversation_history_menu_item"
    const val COPY_MENU_ITEM = "conversation_copy_menu_item"
    const val RAW_MODE_CHIP = "conversation_raw_mode_chip"
    const val AUTO_TROUBLESHOOT_CHIP = "conversation_auto_troubleshoot_chip"
    const val TRANSCRIPT_LIST = "conversation_transcript_list"
    const val INPUT_FIELD = "conversation_input_field"
    const val VOICE_BUTTON = "conversation_voice_button"
    const val SEND_BUTTON = "conversation_send_button"
    const val CONFIRM_RUN_BUTTON = "conversation_confirm_run_button"
    const val CONFIRM_SKIP_BUTTON = "conversation_confirm_skip_button"
}

/**
 * The AI conversation as a full-screen destination (ROADMAP.md v0.9.0; Figma card
 * `node-id=103-2`), replacing the `AlertDialog` `MainActivity` used to show. A pure function of
 * [state] plus the callbacks below, so [MainActivity][net.hlan.sushi.MainActivity] mounts it
 * through a `ComposeView` without owning any of the conversation's own logic.
 *
 * [availabilityStatus] is the Gemini on/off/ready text [state]'s [ConversationStatus.Disconnected]
 * falls back to — computed from `GeminiSettings`/`GeminiClient`/Nano's status the same way the
 * legacy dialog's did, which is Activity-level environment info the ViewModel does not own.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    availabilityStatus: String,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onVoice: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onCopy: () -> Unit,
    onRawModeChange: (Boolean) -> Unit,
    onAutoTroubleshootChange: (Boolean) -> Unit,
    onConfirmPending: () -> Unit,
    onDeclinePending: () -> Unit,
    modifier: Modifier = Modifier
) {
    SushiComposeTheme {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                ConversationTopBar(
                    hostLabel = state.hostLabel,
                    statusSubtitle = statusSubtitle(state.status, availabilityStatus),
                    hasOutput = state.hasOutput,
                    onBack = onBack,
                    onSettings = onSettings,
                    onHistory = onHistory,
                    onCopy = onCopy
                )
                ConversationToggleRow(
                    isRawMode = state.isRawMode,
                    autoTroubleshoot = state.autoTroubleshoot,
                    troubleshootEnabled = !state.isBusy && !state.isRawMode,
                    onRawModeChange = onRawModeChange,
                    onAutoTroubleshootChange = onAutoTroubleshootChange
                )
                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ConversationTranscript(
                    transcript = state.transcript,
                    isBusy = state.isBusy,
                    pendingConfirmation = state.pendingConfirmation,
                    onConfirmPending = onConfirmPending,
                    onDeclinePending = onDeclinePending,
                    modifier = Modifier.weight(1f)
                )
                ConversationInputBar(
                    hostLabel = state.hostLabel,
                    isRawMode = state.isRawMode,
                    isBusy = state.isBusy,
                    onSend = onSend,
                    onVoice = onVoice
                )
            }
        }
    }
}

@Composable
private fun statusSubtitle(status: ConversationStatus, availabilityStatus: String): String = when (status) {
    ConversationStatus.Disconnected -> availabilityStatus
    ConversationStatus.Initializing -> stringResource(R.string.conversation_initializing)
    is ConversationStatus.Connected -> stringResource(R.string.conversation_connected_to, status.identity)
    is ConversationStatus.Failed -> stringResource(R.string.conversation_init_failed, status.message)
}

@Composable
private fun ConversationTopBar(
    hostLabel: String?,
    statusSubtitle: String,
    hasOutput: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onCopy: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag(ConversationScreenTestTags.BACK_BUTTON)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.conversation_screen_back)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = hostLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.gemini_dialog_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = statusSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.testTag(ConversationScreenTestTags.MORE_ACTIONS_BUTTON)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.conversation_screen_more_actions)
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_gemini_settings)) },
                    onClick = { menuExpanded = false; onSettings() },
                    modifier = Modifier.testTag(ConversationScreenTestTags.SETTINGS_MENU_ITEM)
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_gemini_history)) },
                    onClick = { menuExpanded = false; onHistory() },
                    modifier = Modifier.testTag(ConversationScreenTestTags.HISTORY_MENU_ITEM)
                )
                if (hasOutput) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy_command)) },
                        onClick = { menuExpanded = false; onCopy() },
                        modifier = Modifier.testTag(ConversationScreenTestTags.COPY_MENU_ITEM)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationToggleRow(
    isRawMode: Boolean,
    autoTroubleshoot: Boolean,
    troubleshootEnabled: Boolean,
    onRawModeChange: (Boolean) -> Unit,
    onAutoTroubleshootChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = isRawMode,
            onClick = { onRawModeChange(!isRawMode) },
            label = { Text(stringResource(R.string.raw_terminal_mode_label)) },
            modifier = Modifier.testTag(ConversationScreenTestTags.RAW_MODE_CHIP)
        )
        FilterChip(
            selected = autoTroubleshoot,
            onClick = { onAutoTroubleshootChange(!autoTroubleshoot) },
            enabled = troubleshootEnabled,
            label = { Text(stringResource(R.string.troubleshoot_mode_label)) },
            modifier = Modifier.testTag(ConversationScreenTestTags.AUTO_TROUBLESHOOT_CHIP)
        )
    }
}

@Composable
private fun ConversationTranscript(
    transcript: List<TranscriptItem>,
    isBusy: Boolean,
    pendingConfirmation: PendingConfirmation?,
    onConfirmPending: () -> Unit,
    onDeclinePending: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val itemCount = transcript.size + if (pendingConfirmation != null) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ConversationScreenTestTags.TRANSCRIPT_LIST),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(transcript) { index, item ->
            val isLast = index == transcript.lastIndex
            when (item) {
                is TranscriptItem.Turn -> TurnRow(item, showStreamingCursor = isLast && isBusy)
                is TranscriptItem.HostSwitch -> HostSwitchRow(item)
            }
        }
        if (pendingConfirmation != null) {
            item {
                ConfirmCard(pendingConfirmation, onConfirmPending, onDeclinePending)
            }
        }
    }
}

@Composable
private fun TurnRow(item: TranscriptItem.Turn, showStreamingCursor: Boolean) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bubble(
            text = if (item.isRaw) "$ ${item.prompt}" else item.prompt,
            alignEnd = true,
            background = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            monospace = item.isRaw
        )
        if (item.response.isNotBlank()) {
            Bubble(
                text = if (showStreamingCursor) "${item.response} █" else item.response,
                alignEnd = false,
                background = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                monospace = false
            )
        }
    }
}

@Composable
private fun Bubble(
    text: String,
    alignEnd: Boolean,
    background: Color,
    contentColor: Color,
    monospace: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = background,
            contentColor = contentColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun HostSwitchRow(item: TranscriptItem.HostSwitch) {
    val unknown = stringResource(R.string.command_history_unknown_host)
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.conversation_host_switch_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = stringResource(
                R.string.conversation_host_switch_detail,
                item.previousHost ?: unknown,
                item.newHost ?: unknown
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun ConfirmCard(
    pending: PendingConfirmation,
    onConfirmPending: () -> Unit,
    onDeclinePending: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.conversation_confirm_command_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = pending.command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDeclinePending,
                    modifier = Modifier.testTag(ConversationScreenTestTags.CONFIRM_SKIP_BUTTON)
                ) {
                    Text(stringResource(R.string.action_confirm_skip))
                }
                Button(
                    onClick = onConfirmPending,
                    modifier = Modifier.testTag(ConversationScreenTestTags.CONFIRM_RUN_BUTTON)
                ) {
                    Text(stringResource(R.string.action_confirm_run))
                }
            }
        }
    }
}

@Composable
private fun ConversationInputBar(
    hostLabel: String?,
    isRawMode: Boolean,
    isBusy: Boolean,
    onSend: (String) -> Unit,
    onVoice: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val hint = when {
        isRawMode -> stringResource(R.string.raw_terminal_mode_hint)
        !hostLabel.isNullOrBlank() -> stringResource(R.string.conversation_input_hint_with_host, hostLabel)
        else -> stringResource(R.string.conversation_input_hint)
    }
    fun submit() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .testTag(ConversationScreenTestTags.INPUT_FIELD),
            enabled = !isBusy,
            placeholder = { Text(hint) },
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() })
        )
        IconButton(
            onClick = onVoice,
            enabled = !isBusy,
            modifier = Modifier.testTag(ConversationScreenTestTags.VOICE_BUTTON)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = stringResource(R.string.action_gemini_voice)
            )
        }
        IconButton(
            onClick = ::submit,
            enabled = !isBusy && text.isNotBlank(),
            modifier = Modifier.testTag(ConversationScreenTestTags.SEND_BUTTON)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_send),
                contentDescription = stringResource(R.string.action_send_message)
            )
        }
    }
}
