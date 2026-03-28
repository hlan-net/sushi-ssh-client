# MainActivity Integration Plan for Conversation Manager

## Overview
Integrate ConversationManager into MainActivity to enable full conversational AI with target system.

## Key Changes Needed

### 1. Add ConversationManager Instance
```kotlin
// Add after existing lazy properties (line ~47)
private var conversationManager: ConversationManager? = null
```

### 2. Initialize ConversationManager on SSH Connection
When `SshConnectionManager` successfully connects, initialize conversation:

```kotlin
// In connection success callback:
lifecycleScope.launch {
    val useNano = geminiSettings.getUseNano() && isNanoAvailable()
    conversationManager = ConversationManager(
        context = this@MainActivity,
        sshClient = sshConnectionManager.client!!,
        geminiClient = geminiClient,
        geminiNanoClient = nanoClient,
        useNano = useNano
    )
    
    val initResult = conversationManager?.initialize()
    if (initResult?.success == true) {
        updateGeminiStatusWithIdentity(initResult.systemIdentity)
        if (initResult.isDefaultPersona) {
            // Show hint to run initialization Play
            showInitPersonaHint()
        }
    }
}
```

### 3. Update showGeminiDialog()
Modify to handle both voice AND text input:

```kotlin
private fun showGeminiDialog() {
    if (geminiDialog?.isShowing == true) {
        return
    }

    val dialogBinding = DialogGeminiControlsBinding.inflate(layoutInflater)
    geminiDialogBinding = dialogBinding

    // Voice button
    dialogBinding.geminiDialogVoiceButton.setOnClickListener {
        handleGeminiVoice()
    }
    
    // NEW: Send button for text input
    dialogBinding.geminiDialogSendButton.setOnClickListener {
        val text = dialogBinding.geminiDialogTextInput.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            dialogBinding.geminiDialogTextInput.text?.clear()
            handleUserMessage(text, isVoice = false)
        }
    }
    
    // NEW: IME action (keyboard enter)
    dialogBinding.geminiDialogTextInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            dialogBinding.geminiDialogSendButton.performClick()
            true
        } else {
            false
        }
    }

    // Settings button
    dialogBinding.geminiDialogSettingsButton.setOnClickListener {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // Transcript adapter (keep existing)
    val adapter = GeminiTranscriptAdapter(geminiTranscript)
    transcriptAdapter = adapter
    dialogBinding.geminiTranscriptRecycler.layoutManager =
        LinearLayoutManager(this).also { it.stackFromEnd = true }
    dialogBinding.geminiTranscriptRecycler.adapter = adapter
    if (geminiTranscript.isNotEmpty()) {
        dialogBinding.geminiTranscriptLabel.visibility = View.VISIBLE
        dialogBinding.geminiTranscriptRecycler.visibility = View.VISIBLE
    }

    val dialog = AlertDialog.Builder(this)
        .setView(dialogBinding.root)
        .setNegativeButton(R.string.phrase_cancel, null)
        .create()
    dialog.setOnDismissListener {
        geminiDialog = null
        geminiDialogBinding = null
        transcriptAdapter = null
    }
    geminiDialog = dialog
    updateGeminiState()
    updateGeminiDialogState()
    dialog.show()
}
```

### 4. Create handleUserMessage()
NEW method to process text or voice input:

```kotlin
private fun handleUserMessage(message: String, isVoice: Boolean) {
    if (conversationManager == null || !conversationManager!!.isInitialized()) {
        Toast.makeText(
            this,
            getString(R.string.conversation_init_failed, "Not connected"),
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    lastGeminiPrompt = message
    isGeminiRequestRunning = true
    updateGeminiDialogState()

    lifecycleScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                conversationManager!!.processUserMessage(message)
            }

            withContext(Dispatchers.Main) {
                isGeminiRequestRunning = false

                if (!result.success) {
                    lastGeminiOutput = result.systemResponse
                    appendSessionLog("Error: ${result.systemResponse}")
                } else {
                    lastGeminiOutput = result.systemResponse

                    // Handle different result types
                    when {
                        result.needsConfirmation -> {
                            // Show confirmation dialog
                            showCommandConfirmationDialog(
                                message,
                                result.systemResponse,
                                result.commandToConfirm!!
                            )
                        }
                        
                        result.commandBlocked -> {
                            // Command was blocked
                            appendSessionLog("Blocked: ${result.commandAttempted}")
                        }
                        
                        result.commandExecuted != null -> {
                            // Command was executed
                            appendSessionLog(
                                "Executed: ${result.commandExecuted}\n" +
                                "Result: ${if (result.commandSuccess) "success" else "failed"}"
                            )
                        }
                    }
                }

                // Update transcript
                geminiTranscript.add(GeminiTranscriptEntry(
                    prompt = message,
                    response = result.systemResponse
                ))
                transcriptAdapter?.let { adapter ->
                    adapter.notifyItemInserted(geminiTranscript.size - 1)
                    geminiDialogBinding?.geminiTranscriptLabel?.visibility = View.VISIBLE
                    geminiDialogBinding?.geminiTranscriptRecycler?.apply {
                        visibility = View.VISIBLE
                        scrollToPosition(geminiTranscript.size - 1)
                    }
                }

                updateGeminiDialogState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
            withContext(Dispatchers.Main) {
                isGeminiRequestRunning = false
                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                updateGeminiDialogState()
            }
        }
    }
}
```

### 5. Create showCommandConfirmationDialog()
NEW method for confirming potentially dangerous commands:

```kotlin
private fun showCommandConfirmationDialog(
    userMessage: String,
    initialResponse: String,
    command: String
) {
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.conversation_confirm_command_title))
        .setMessage(getString(R.string.conversation_confirm_command_message, command))
        .setPositiveButton(android.R.string.ok) { _, _ ->
            executeConfirmedCommand(userMessage, initialResponse, command)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun executeConfirmedCommand(
    userMessage: String,
    initialResponse: String,
    command: String
) {
    isGeminiRequestRunning = true
    updateGeminiDialogState()

    lifecycleScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                conversationManager!!.executeConfirmedCommand(
                    userMessage,
                    initialResponse,
                    command
                )
            }

            withContext(Dispatchers.Main) {
                isGeminiRequestRunning = false
                lastGeminiOutput = result.systemResponse

                if (result.commandExecuted != null) {
                    appendSessionLog(
                        "Executed (confirmed): ${result.commandExecuted}\n" +
                        "Result: ${if (result.commandSuccess) "success" else "failed"}"
                    )
                }

                // Update last transcript entry with final response
                if (geminiTranscript.isNotEmpty()) {
                    val lastIdx = geminiTranscript.size - 1
                    geminiTranscript[lastIdx] = GeminiTranscriptEntry(
                        prompt = userMessage,
                        response = result.systemResponse
                    )
                    transcriptAdapter?.notifyItemChanged(lastIdx)
                }

                updateGeminiDialogState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing confirmed command", e)
            withContext(Dispatchers.Main) {
                isGeminiRequestRunning = false
                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                updateGeminiDialogState()
            }
        }
    }
}
```

### 6. Update handleGeminiVoice()
Simplify to call handleUserMessage():

```kotlin
private fun handleGeminiVoice() {
    // Check microphone permission first (existing code)
    // ...
    
    // Launch voice recognizer (existing code)
    // In voiceResultLauncher callback:
    if (!voiceText.isNullOrEmpty()) {
        handleUserMessage(voiceText, isVoice = true)
    }
}
```

### 7. Clear ConversationManager on Disconnect
```kotlin
// In disconnect handler:
private fun handleDisconnect() {
    conversationManager?.clearHistory()
    conversationManager = null
    // ... existing disconnect logic
}
```

### 8. Handle Session Reconnect
When reconnecting (not a fresh connection), check if we should reinitialize:

```kotlin
// In reconnect logic:
if (conversationManager != null && !conversationManager!!.isInitialized()) {
    // Reinitialize after reconnect
    lifecycleScope.launch {
        conversationManager?.initialize()
    }
}
```

## UI State Management

### Update Status Text
Show system identity when connected:

```kotlin
private fun updateGeminiStatusWithIdentity(identity: String?) {
    terminalPageBinding?.geminiStatusText?.text = if (identity != null) {
        getString(R.string.conversation_connected_to, identity)
    } else {
        getString(R.string.gemini_status_ready)
    }
}
```

### Disable Inputs When Busy
```kotlin
private fun updateGeminiDialogState() {
    val dialogBinding = geminiDialogBinding ?: return
    
    val isBusy = isGeminiRequestRunning
    
    dialogBinding.geminiDialogTextInput.isEnabled = !isBusy
    dialogBinding.geminiDialogSendButton.isEnabled = !isBusy
    dialogBinding.geminiDialogVoiceButton.isEnabled = !isBusy
    
    dialogBinding.geminiDialogProgressBar.visibility = if (isBusy) {
        View.VISIBLE
    } else {
        View.GONE
    }
    
    // Copy button visibility (existing logic)
    // ...
}
```

## Testing Checklist

- [ ] Voice input triggers conversation
- [ ] Text input triggers conversation
- [ ] Commands classified correctly (SAFE/CONFIRM/BLOCKED)
- [ ] Confirmation dialog appears for CONFIRM commands
- [ ] Blocked commands show error message
- [ ] Safe commands execute automatically
- [ ] Command results interpreted conversationally
- [ ] Transcript updates correctly
- [ ] Session logs include commands
- [ ] Reconnect preserves conversation
- [ ] Disconnect clears state
- [ ] System identity displayed when connected

## Implementation Order

1. ✅ Add ConversationManager instance variable
2. ✅ Create handleUserMessage() method
3. ✅ Create showCommandConfirmationDialog() methods
4. ✅ Update showGeminiDialog() for text input
5. ✅ Update handleGeminiVoice() to use handleUserMessage()
6. ✅ Add initialization on SSH connect
7. ✅ Add cleanup on disconnect
8. ✅ Update UI state management
9. ✅ Test end-to-end flow

## Notes

- Keep existing GeminiTranscriptEntry and adapter for now (minimal changes)
- Later can enhance to show command execution inline
- Session logs provide audit trail of commands
- ConversationManager handles all LLM interaction and safety
