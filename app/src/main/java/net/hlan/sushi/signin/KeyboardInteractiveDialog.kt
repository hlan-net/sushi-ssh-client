package net.hlan.sushi.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import net.hlan.sushi.KeyboardInteractiveRequest
import net.hlan.sushi.R

/**
 * The "Server sign-in" dialog for a `keyboard-interactive` round the stored password cannot
 * answer (#191, [Figma](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=131-2)).
 *
 * A pure function of [request] plus callbacks, so `KeyboardInteractiveDialogTest` feeds it a
 * request directly. The answers live only in this composition: they are never stored, and the
 * saved password is never prefilled, even for a `Password:` prompt. A repeated round is shown
 * the same way, with no "try again" wording — a new round can be the next step (password, then
 * code), not a rejection.
 *
 * Tapping outside does not cancel, so a stray tap cannot throw away a typed code; Cancel and
 * Back do, and a cancel shows no error.
 */
@Composable
fun KeyboardInteractiveDialog(
    request: KeyboardInteractiveRequest,
    onSubmit: (List<String>) -> Unit,
    onCancel: () -> Unit
) {
    val answers = remember(request) { mutableStateListOf(*Array(request.prompts.size) { "" }) }
    val firstField = remember { FocusRequester() }
    val submit = { onSubmit(answers.toList()) }

    LaunchedEffect(request) {
        if (request.prompts.isNotEmpty()) {
            firstField.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag(KeyboardInteractiveDialogTestTags.DIALOG),
        title = { Text(stringResource(R.string.keyboard_interactive_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (request.isJumpHost) {
                        stringResource(R.string.keyboard_interactive_jump_host, request.hostLabel)
                    } else {
                        request.hostLabel
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.testTag(KeyboardInteractiveDialogTestTags.HOST)
                )
                serverTexts(request).forEach { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(KeyboardInteractiveDialogTestTags.INSTRUCTION)
                    )
                }
                request.prompts.forEachIndexed { index, prompt ->
                    val isLast = index == request.prompts.lastIndex
                    OutlinedTextField(
                        value = answers[index],
                        onValueChange = { answers[index] = it },
                        label = { Text(prompt.text) },
                        singleLine = true,
                        visualTransformation = if (prompt.echo) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (prompt.echo) KeyboardType.Text else KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = if (isLast) ImeAction.Done else ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(KeyboardInteractiveDialogTestTags.field(index))
                            .let { if (index == 0) it.focusRequester(firstField) else it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = submit,
                modifier = Modifier.testTag(KeyboardInteractiveDialogTestTags.CONTINUE)
            ) {
                Text(stringResource(R.string.keyboard_interactive_continue))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(KeyboardInteractiveDialogTestTags.CANCEL)
            ) {
                Text(stringResource(R.string.keyboard_interactive_cancel))
            }
        }
    )
}

/** The server's name and instruction for the round, each once and only when it sent one. */
private fun serverTexts(request: KeyboardInteractiveRequest): List<String> =
    listOf(request.name, request.instruction).filter { it.isNotBlank() }.distinct()
