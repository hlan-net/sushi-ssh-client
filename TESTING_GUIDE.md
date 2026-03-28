# Testing Guide for Sushi AI Persona Feature

## Current Build Environment Issue

**Issue**: AAPT2 daemon is x86_64 architecture but the current system is ARM-based (aarch64).  
**Impact**: Cannot build APK or run instrumented tests from this environment.  
**Workaround**: Build and test from x86_64 Linux machine or use Android Studio on any platform.

---

## Manual Testing (Recommended for Phase 2)

Since automated tests require building the APK, here's what to test manually:

### Prerequisites
1. Android device with USB debugging enabled
2. x86_64 machine with Android SDK (or Android Studio)
3. Raspberry Pi accessible via SSH
4. Sushi app built and installed

### Setup Steps

```bash
# On x86_64 machine with Android SDK:
cd /path/to/sushi
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Test Suite: Phase 2 Conversation Feature

### Test 1: Installation and Initialization ✅

**Objective**: Verify SUSHI.md creation on target system

**Steps**:
1. Open Sushi app
2. Configure SSH connection to Raspberry Pi
3. Navigate to Plays tab
4. Find "Initialize AI Persona" Play
5. Run the Play
6. Wait for completion

**Expected Results**:
- ✅ Play completes successfully
- ✅ Message shows installation path

**Verification on Pi**:
```bash
ssh pi@raspberrypi
cat ~/.config/sushi/SUSHI.md
# Should show generated persona configuration

ls -la ~/.config/sushi/
# Should show: SUSHI.md, config.conf, scripts/, knowledge/

cat ~/.config/sushi/scripts/profile_system.sh
# Should be executable script

ls ~/.sushi_logs/
# Directory should exist (empty initially)
```

---

### Test 2: SSH Connection Tracking ✅

**Objective**: Verify SshConnectionHolder tracks connection state

**Steps**:
1. Open Sushi app
2. Tap "Start Session" or "Return to Terminal"
3. Observe connection status
4. App should connect to Pi via SSH
5. Terminal should show "Connected to [hostname]"

**Expected Results**:
- ✅ Terminal connects successfully
- ✅ Terminal shows "Connected to raspberrypi" (or your hostname)
- ✅ Terminal is interactive (can type commands)

**What's Happening Behind the Scenes**:
- TerminalActivity calls `SshConnectionHolder.setActiveConnection()`
- MainActivity receives `onConnected()` callback
- ConversationManager begins initialization

---

### Test 3: Conversation Initialization ✅

**Objective**: Verify ConversationManager reads SUSHI.md

**Steps**:
1. With Terminal connected (from Test 2)
2. Return to MainActivity (back button)
3. Look at Gemini status text (top of Terminal tab)
4. Should show "Connected to: [system identity]"

**Expected Results**:
- ✅ Status shows system identity (e.g., "Connected to: raspberrypi")
- ✅ If SUSHI.md not initialized, shows tip message
- ✅ No errors or crashes

**If Status Shows Error**:
- Check that "Initialize AI Persona" Play was run
- SSH to Pi and verify `~/.config/sushi/SUSHI.md` exists
- Check Logcat for errors

---

### Test 4: Text Input - Safe Command ✅

**Objective**: Verify text input and safe command auto-execution

**Steps**:
1. With Terminal connected
2. Tap Gemini button (microphone icon in Terminal tab)
3. Dialog opens with text input field
4. Type: "What's your uptime?"
5. Tap send button (paper plane icon) OR press Enter on keyboard
6. Wait for response

**Expected Results**:
- ✅ Input field clears after send
- ✅ Progress indicator shows (spinning)
- ✅ Response appears in transcript
- ✅ Response is conversational (e.g., "I've been running for 5 days")
- ✅ Session log updated with "Executed: uptime -p"

**What Should Happen**:
1. ConversationManager.processUserMessage() called
2. Gemini receives SUSHI.md context + user message
3. LLM responds with: "EXECUTE: uptime -p"
4. CommandSafety classifies as SAFE
5. Command auto-executes via SSH
6. Result sent back to Gemini for interpretation
7. Natural language response displayed

---

### Test 5: Voice Input ✅

**Objective**: Verify voice input still works

**Steps**:
1. With Gemini dialog open
2. Tap "Voice" button
3. Grant microphone permission (if needed)
4. Speak clearly: "What's your temperature?"
5. Wait for voice recognition
6. Wait for response

**Expected Results**:
- ✅ Voice recognition dialog appears
- ✅ Text recognized correctly
- ✅ Same flow as text input
- ✅ If Raspberry Pi: executes `vcgencmd measure_temp`
- ✅ Response: "I am running at XX degrees Celsius..."

---

### Test 6: Command Requiring Confirmation ⚠️

**Objective**: Verify CONFIRM safety level works

**Steps**:
1. With Gemini dialog open
2. Type: "Restart the nginx service"
3. Send message
4. Wait for LLM response

**Expected Results**:
- ✅ LLM generates: "EXECUTE: systemctl restart nginx"
- ✅ CommandSafety classifies as CONFIRM
- ✅ Confirmation dialog appears with command shown
- ✅ Dialog shows: "Execute this command?\n\nsystemctl restart nginx"
- ✅ Has OK and Cancel buttons

**Test Cancel**:
1. Tap Cancel
2. Command should NOT execute
3. Transcript shows initial message only

**Test Confirm**:
1. Repeat test
2. Tap OK on confirmation dialog
3. Command executes
4. Result interpreted conversationally
5. Session log shows: "Executed (confirmed): systemctl restart nginx"

---

### Test 7: Blocked Command 🚫

**Objective**: Verify BLOCKED safety level prevents execution

**Steps**:
1. With Gemini dialog open
2. Type: "Shut down the system"
3. Send message
4. Wait for response

**Expected Results**:
- ✅ LLM might generate: "EXECUTE: shutdown -h now"
- ✅ CommandSafety classifies as BLOCKED
- ✅ Command does NOT execute
- ✅ Response shows: "[Command blocked: This command is blocked for safety...]"
- ✅ Session log shows: "Blocked: shutdown -h now"
- ✅ Pi remains running (obviously!)

**Other Commands to Test**:
- "Remove all files" → should block `rm -rf /`
- "Format the disk" → should block `mkfs`
- "Reboot now" → should block `reboot`

---

### Test 8: Conversation Context ✅

**Objective**: Verify LLM maintains conversation history

**Steps**:
1. Clear transcript (close and reopen dialog)
2. Ask: "What directory am I in?"
3. Wait for response (should execute `pwd`)
4. Then ask: "List the files here"
5. Wait for response

**Expected Results**:
- ✅ First query: Executes pwd, tells you current directory
- ✅ Second query: Executes ls in that directory
- ✅ LLM understands "here" refers to the directory from first question
- ✅ Conversation history includes both turns

**Advanced Context Test**:
1. Ask: "What's my CPU usage?"
2. Then: "Is that normal?"
3. LLM should reference the CPU % from first answer

---

### Test 9: Disconnect and Reconnect ✅

**Objective**: Verify state management on disconnect/reconnect

**Steps**:
1. With conversation active
2. Navigate to Terminal
3. Disconnect (menu → disconnect, or back button to MainActivity)
4. Check Gemini status - should show "Disconnected" or default status
5. Open Gemini dialog - transcript might still show old conversation
6. Close dialog, reconnect to Terminal
7. Wait for connection
8. Check status again - should show "Connected to: [identity]"
9. Open Gemini dialog, ask new question

**Expected Results**:
- ✅ On disconnect: SshConnectionHolder.clearActiveConnection() called
- ✅ MainActivity receives onDisconnected()
- ✅ ConversationManager.clearHistory() called
- ✅ Status updates to disconnected state
- ✅ On reconnect: Full reinitialization
- ✅ Conversation history starts fresh
- ✅ Can converse again normally

---

### Test 10: Error Handling ⚠️

**Objective**: Verify graceful error handling

**Test 10a: No SUSHI.md (Default Persona)**
1. SSH to Pi and rename SUSHI.md: `mv ~/.config/sushi/SUSHI.md{,.bak}`
2. Reconnect in app
3. Try to converse

**Expected**:
- ✅ Shows tip: "Run 'Initialize AI Persona' Play"
- ✅ Conversation still works with generic context
- ✅ No crash

**Test 10b: Not Connected**
1. Don't connect to any host
2. Open Gemini dialog
3. Try to type/send message

**Expected**:
- ✅ Toast: "Failed to initialize conversation: Not connected"
- ✅ Or falls back to old command generation mode
- ✅ No crash

**Test 10c: Connection Lost During Conversation**
1. Start conversation
2. During LLM processing, disconnect network on Pi
3. Wait for timeout

**Expected**:
- ✅ Error message displayed
- ✅ App doesn't crash
- ✅ Can reconnect and try again

---

## Automated Tests (When x86_64 Available)

### Unit Tests (JVM)

```bash
# Run on any architecture
./gradlew test
./gradlew testDebugUnitTest
```

**Expected to Pass**:
- All existing unit tests
- No new unit tests added yet (can add in Phase 3)

### Instrumented Tests (Device Required)

```bash
# Requires Android device connected via USB
./gradlew connectedDebugAndroidTest
```

**Available Test Suites**:
1. **ExampleInstrumentedTest** - Basic app context test
2. **JschRuntimeTest** - SSH library sanity check
3. **DeviceQaSuiteTest** - Comprehensive UI tap-through
4. **LocalSshIntegrationTest** - Real SSH connection test (requires config)

**For Phase 2 Testing**:
Focus on manual testing above. Automated UI tests for conversation feature can be added in Phase 3.

---

## Performance Testing

### Memory Usage
1. Connect Android Studio Profiler
2. Connect to Pi
3. Open conversation dialog
4. Send 10-20 messages
5. Monitor memory usage

**Expected**:
- Stable memory usage
- No memory leaks
- ConversationManager keeps only last 10 turns

### Battery Impact
1. Enable battery monitoring
2. Use app normally with conversations
3. Check battery drain

**Expected**:
- No background processing
- Battery usage only during active conversation
- Comparable to web browsing

---

## Regression Testing

Verify existing features still work:

### Terminal Mode ✅
- [ ] Can connect via SSH
- [ ] Terminal displays output correctly
- [ ] Can type commands
- [ ] Enter, Tab, Backspace work
- [ ] Ctrl+C, Ctrl+D work
- [ ] Screen rotation doesn't disconnect
- [ ] Terminal log saves on disconnect

### Plays System ✅
- [ ] Can list plays
- [ ] Can run a play
- [ ] Play output displays
- [ ] Play with parameters works
- [ ] Managed plays present
- [ ] "Initialize AI Persona" play works

### Old Gemini Mode (Fallback) ✅
- [ ] When not connected, old mode still works
- [ ] Voice generates command
- [ ] Can copy command to clipboard
- [ ] Transcript shows commands

### Settings ✅
- [ ] All settings pages accessible
- [ ] Host management works
- [ ] SSH key generation works
- [ ] Gemini settings work
- [ ] Drive settings work

---

## Known Issues (Not Bugs)

1. **AAPT2 Build Error on ARM**: Environment issue, not code issue
2. **Old Transcript Format**: Still using simple prompt/response (Phase 3 enhancement)
3. **No Target Logging Yet**: ~/.sushi_logs/ not written to (Phase 3 feature)

---

## Bug Reporting Template

If you find a bug during testing:

```
**Bug Title**: [Short description]

**Environment**:
- Android Version: 
- Device Model: 
- Sushi Version: 0.5.0-dev
- Pi OS Version: 

**Steps to Reproduce**:
1. 
2. 
3. 

**Expected Behavior**:

**Actual Behavior**:

**Logcat Output** (if available):
```

---

## Success Criteria

Phase 2 testing is complete when:

- [ ] All 10 manual tests pass
- [ ] No critical bugs found
- [ ] Existing features still work (regression pass)
- [ ] Performance is acceptable
- [ ] Error handling works gracefully

---

## Next Steps After Testing

1. **Document any bugs found**
2. **Fix critical bugs**
3. **Update CHANGELOG.md** with v0.5.0 changes
4. **Update README.md** with new features
5. **Create release build**
6. **Tag release**: `git tag v0.5.0`

---

**When ready to test, build on x86_64 machine or in Android Studio!**

*Testing Guide v1.0*  
*Last Updated: 2026-03-29*
