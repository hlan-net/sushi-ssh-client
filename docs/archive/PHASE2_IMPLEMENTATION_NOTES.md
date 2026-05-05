# Phase 2 Implementation Notes

## Status: In Progress

**Date**: 2026-03-28  
**Phase**: 2 of 3 - Conversation UI & Session Management

---

## Completed Components ✅

### 1. ConversationManager
**File**: `app/src/main/java/net/hlan/sushi/ConversationManager.kt`

Core conversation orchestration:
- Session state management
- SUSHI.md context loading
- LLM interaction (Nano or Cloud)
- Command execution pipeline with safety checks
- Conversation history (last 10 turns)
- Auto-execution for SAFE commands
- Confirmation flow for CONFIRM commands
- Blocking for BLOCKED commands

**Key Methods**:
- `initialize()` - Read SUSHI.md from target on connection
- `processUserMessage()` - Handle user input, classify and execute commands
- `executeConfirmedCommand()` - Execute after user confirmation
- `getHistory()` - Get conversation turns
- `clearHistory()` - Reset on disconnect

### 2. Enhanced Dialog Layout
**File**: `app/src/main/res/layout/dialog_gemini_controls.xml`

Updated Gemini dialog with:
- Text input field with hint
- Send button (Material3 icon button)
- Voice button (existing, restyled)
- Settings button
- Conversation transcript (RecyclerView)
- Progress indicator

**New UI Elements**:
- `geminiDialogTextInput` - TextInputEditText for typing
- `geminiDialogSendButton` - IconButton to send
- IME action support (keyboard Enter key)

### 3. SshConnectionHolder
**File**: `app/src/main/java/net/hlan/sushi/SshConnectionHolder.kt`

Singleton to share SSH connection between activities:
- `setActiveConnection()` - Called by TerminalActivity on connect
- `clearActiveConnection()` - Called by TerminalActivity on disconnect
- `getActiveClient()` - Accessed by MainActivity for conversation
- `isConnected()` - Check connection state
- Listener pattern for connection state changes

### 4. Resources
**Files**:
- `app/src/main/res/drawable/ic_send.xml` - Send icon
- `app/src/main/res/values/strings.xml` - Conversation strings added

**New Strings**:
- `conversation_input_hint` - "Ask the system..."
- `action_send_message` - "Send message"
- `conversation_confirm_command_title` - "Confirm Command"
- `conversation_confirm_command_message` - Command confirmation message
- `conversation_command_blocked` - Blocked message
- `conversation_executing` - Executing indicator
- `conversation_initializing` - Initializing message
- `conversation_init_failed` - Init failure message
- `conversation_connected_to` - Connection success message

---

## Remaining Work 🚧

### Phase 2A: Terminal Activity Integration

**File**: `app/src/main/java/net/hlan/sushi/TerminalActivity.kt`

**Changes Needed**:

1. **On SSH Connect Success**:
```kotlin
// After successful SSH connection:
SshConnectionHolder.setActiveConnection(sshClient, hostConfig)
```

2. **On SSH Disconnect**:
```kotlin
// When disconnecting:
SshConnectionHolder.clearActiveConnection()
```

**Implementation Steps**:
- [ ] Find connection success callback in TerminalActivity
- [ ] Add `SshConnectionHolder.setActiveConnection()` call
- [ ] Find disconnect handler
- [ ] Add `SshConnectionHolder.clearActiveConnection()` call
- [ ] Test connection/disconnection flow

### Phase 2B: MainActivity Integration

**File**: `app/src/main/java/net/hlan/sushi/MainActivity.kt`

**Changes Needed**:

1. **Add Instance Variables** (after line ~56):
```kotlin
private var conversationManager: ConversationManager? = null
private var connectionListener: SshConnectionHolder.ConnectionListener? = null
```

2. **Implement ConnectionListener** (in onCreate or as inner class):
```kotlin
connectionListener = object : SshConnectionHolder.ConnectionListener {
    override fun onConnected() {
        lifecycleScope.launch {
            initializeConversation()
        }
    }

    override fun onDisconnected() {
        lifecycleScope.launch(Dispatchers.Main) {
            conversationManager?.clearHistory()
            conversationManager = null
            updateConversationUI()
        }
    }
}
SshConnectionHolder.addListener(connectionListener!!)
```

3. **Add initializeConversation() Method**:
```kotlin
private suspend fun initializeConversation() {
    val sshClient = SshConnectionHolder.getActiveClient() ?: return
    
    withContext(Dispatchers.Main) {
        // Show initializing status
        updateConversationStatus(getString(R.string.conversation_initializing))
    }

    val useNano = geminiSettings.getUseNano() && isNanoAvailable()
    conversationManager = ConversationManager(
        context = this,
        sshClient = sshClient,
        geminiClient = geminiClient,
        geminiNanoClient = nanoClient,
        useNano = useNano
    )

    val initResult = withContext(Dispatchers.IO) {
        conversationManager?.initialize()
    }

    withContext(Dispatchers.Main) {
        if (initResult?.success == true) {
            val identity = initResult.systemIdentity ?: "Unknown System"
            updateConversationStatus(
                getString(R.string.conversation_connected_to, identity)
            )
            
            if (initResult.isDefaultPersona) {
                // Show hint to initialize persona
                Toast.makeText(
                    this@MainActivity,
                    "Tip: Run 'Initialize AI Persona' Play for better experience",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            updateConversationStatus(
                getString(R.string.conversation_init_failed, initResult?.message ?: "Unknown error")
            )
        }
    }
}
```

4. **Add handleUserMessage() Method** (see MAINACTIVITY_INTEGRATION_PLAN.md for full implementation)

5. **Add showCommandConfirmationDialog() Methods** (see plan document)

6. **Update showGeminiDialog()**:
   - Wire up send button: `dialogBinding.geminiDialogSendButton.setOnClickListener { ... }`
   - Wire up text input IME action
   - Keep existing voice button functionality

7. **Update handleGeminiVoice()**:
   - Change to call `handleUserMessage(voiceText, isVoice = true)` instead of direct Gemini API call

8. **Add onDestroy() cleanup**:
```kotlin
override fun onDestroy() {
    super.onDestroy()
    connectionListener?.let { SshConnectionHolder.removeListener(it) }
}
```

**Implementation Steps**:
- [ ] Add instance variables
- [ ] Add ConnectionListener implementation
- [ ] Create initializeConversation() method
- [ ] Create handleUserMessage() method
- [ ] Create showCommandConfirmationDialog() method
- [ ] Create executeConfirmedCommand() method
- [ ] Update showGeminiDialog() with text input handlers
- [ ] Update handleGeminiVoice() to use handleUserMessage()
- [ ] Add onDestroy() cleanup
- [ ] Create updateConversationStatus() helper
- [ ] Test full flow

### Phase 2C: Polish & Edge Cases

**Tasks**:
- [ ] Handle conversation when not connected (disable inputs, show message)
- [ ] Handle reconnection (reinitialize conversation if needed)
- [ ] Improve progress indicators during command execution
- [ ] Add "executing" state to transcript items
- [ ] Test with various command types (safe, confirm, blocked)
- [ ] Test with Gemini Nano vs Cloud
- [ ] Test conversation history context
- [ ] Error handling improvements

---

## Architecture Summary

```
User                MainActivity               SshConnectionHolder      TerminalActivity
 |                       |                            |                        |
 |                       |                            |                        |
 |   [Connect to Pi]     |                            |                        |
 |---------------------->|                            |                        |
 |                       |    Launch TerminalActivity |                        |
 |                       |---------------------------------------------->|
 |                       |                            |                        |
 |                       |                            |     SSH Connect        |
 |                       |                            |<-----------------------|
 |                       |                            |  setActiveConnection() |
 |                       |   onConnected()            |                        |
 |                       |<---------------------------|                        |
 |                       |  initializeConversation()  |                        |
 |                       |    ┌─────────────┐         |                        |
 |                       |    │ Read        │         |                        |
 |                       |    │ SUSHI.md    │<--------|  (via SSH)             |
 |                       |    └─────────────┘         |                        |
 |                       |                            |                        |
 |   [Open Gemini Dialog]|                            |                        |
 |---------------------->|                            |                        |
 |                       |  Show dialog with          |                        |
 |                       |  text input + voice        |                        |
 |                       |                            |                        |
 |   "What's your temp?" |                            |                        |
 |---------------------->|                            |                        |
 |                       |  ConversationManager       |                        |
 |                       |  .processUserMessage()     |                        |
 |                       |    ┌─────────────┐         |                        |
 |                       |    │ Gemini LLM  │         |                        |
 |                       |    │ with        │         |                        |
 |                       |    │ SUSHI.md    │         |                        |
 |                       |    │ context     │         |                        |
 |                       |    └─────────────┘         |                        |
 |                       |         |                  |                        |
 |                       |    Response:               |                        |
 |                       |    EXECUTE: vcgencmd...    |                        |
 |                       |         |                  |                        |
 |                       |    CommandSafety           |                        |
 |                       |    .classify() → SAFE      |                        |
 |                       |         |                  |                        |
 |                       |    Execute via SSH ------->|---------------------->|
 |                       |         |                  |     sendCommand()      |
 |                       |    temp=52.0'C             |<----------------------|
 |                       |<-----------------------    |                        |
 |                       |         |                  |                        |
 |                       |    Ask LLM to interpret    |                        |
 |                       |         |                  |                        |
 |  "I'm at 52°C..."     |         |                  |                        |
 |<----------------------|         |                  |                        |
 |                       |                            |                        |
 |   [Disconnect]        |                            |                        |
 |---------------------->|                            |                        |
 |                       |  close TerminalActivity    |                        |
 |                       |---------------------------------------------->|
 |                       |                            |     disconnect()       |
 |                       |                            |<-----------------------|
 |                       |                            | clearActiveConnection()|
 |                       |   onDisconnected()         |                        |
 |                       |<---------------------------|                        |
 |                       |  Clear conversation        |                        |
 |                       |                            |                        |
```

---

## Testing Plan

### Unit Testing (Future)
- [ ] CommandSafety classifier tests
- [ ] PersonaClient SUSHI.md parsing tests
- [ ] ConversationManager state management tests

### Integration Testing
1. **Connection Flow**:
   - [ ] Connect via TerminalActivity
   - [ ] Verify SshConnectionHolder updated
   - [ ] Verify MainActivity receives onConnected()
   - [ ] Verify conversation initialized
   - [ ] Verify SUSHI.md read from target

2. **Text Input**:
   - [ ] Type message in dialog
   - [ ] Click send button
   - [ ] Verify message processed
   - [ ] Verify response displayed

3. **Voice Input**:
   - [ ] Click voice button
   - [ ] Speak message
   - [ ] Verify message processed
   - [ ] Verify response displayed

4. **Command Execution**:
   - [ ] SAFE command (e.g., "uptime") - auto-executes
   - [ ] CONFIRM command (e.g., "restart nginx") - shows dialog
   - [ ] BLOCKED command (e.g., "shutdown") - shows error
   - [ ] Verify results interpreted conversationally

5. **Disconnect/Reconnect**:
   - [ ] Disconnect from TerminalActivity
   - [ ] Verify conversation cleared
   - [ ] Reconnect
   - [ ] Verify conversation reinitialized

### Manual Testing Checklist
- [ ] Fresh install on real device
- [ ] Run "Initialize AI Persona" Play on test Pi
- [ ] Edit SUSHI.md to customize system
- [ ] Connect via terminal
- [ ] Open Gemini dialog - verify identity shown
- [ ] Ask simple question - verify response
- [ ] Ask for command - verify execution
- [ ] Test each command safety level
- [ ] Test voice input
- [ ] Test text input
- [ ] Test conversation history context
- [ ] Disconnect and verify cleanup
- [ ] Reconnect and verify reinitialization

---

## Known Limitations

1. **No Multi-Host Support Yet**: Only one active SSH connection at a time
2. **No Conversation Persistence**: History cleared on disconnect
3. **No Target-Side Logging Yet**: Phase 2 doesn't implement ~/.sushi_logs/ writing
4. **Basic Transcript UI**: Still uses simple prompt/response format, not chat bubbles

These will be addressed in Phase 3 (Polish) or future versions.

---

## Next Steps

1. ✅ Wire up TerminalActivity to set/clear SshConnectionHolder
2. ⏳ Implement MainActivity integration (8 steps above)
3. ⏳ Test end-to-end conversation flow
4. ⏳ Fix bugs and edge cases
5. ⏳ Phase 3: Polish, logging, localization

---

*Last updated: 2026-03-28 23:45*
