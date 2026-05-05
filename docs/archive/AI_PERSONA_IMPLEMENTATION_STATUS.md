# AI Persona Implementation Status

## Overview

Implementation of target-system-resident AI persona for conversational interaction with Raspberry Pi and other Linux systems via SSH.

**Status**: Foundation Complete (Phase 1 of 3)  
**Date**: 2026-03-28  
**Version**: 0.5.0-dev

---

## Architecture Summary

### Core Concept
The AI persona lives **on the target system** (Raspberry Pi), not in the Android app. The app is just a communication interface - the user talks TO the target system, not to their phone.

### Key Components

```
Android App                     Target System (Raspberry Pi)
-----------                     ----------------------------
PersonaClient     <--SSH-->     ~/.config/sushi/SUSHI.md
  ↓                                 ↑
GeminiClient                    (Describes the system)
  ↓
User conversation
```

**Flow**:
1. User connects to host via SSH
2. App reads `~/.config/sushi/SUSHI.md` from target
3. SUSHI.md describes the system (hardware, OS, personality, available commands, safety rules)
4. User talks → Gemini gets SUSHI.md context → Generates response AS the system
5. Commands executed on target → Results formatted conversationally

---

## Completed Components ✅

### 1. Target System Installation Script
**File**: `install-scripts/initialize-sushi-persona.sh`

Bash script that installs the persona framework on target systems:
- Creates `~/.config/sushi/` directory structure
- Generates `SUSHI.md` with auto-detected system info
- Installs profiling script (`profile_system.sh`)
- Creates config file for custom settings
- Detects Raspberry Pi and adds Pi-specific commands
- Sets up `~/.sushi_logs/` for conversation logging

**Usage**:
```bash
# Run via Sushi app's "Initialize AI Persona" Play
# Or manually: bash initialize-sushi-persona.sh
```

### 2. Managed Play: "Initialize AI Persona"
**File**: `app/src/main/java/net/hlan/sushi/ManagedPlays.kt`

Added new managed Play that installs persona framework on target with one tap:
- Embedded initialization script (inline in Kotlin)
- Auto-generated SUSHI.md with system detection
- No parameters needed - fully automatic

### 3. PersonaClient
**File**: `app/src/main/java/net/hlan/sushi/PersonaClient.kt`

Android client for interacting with target system persona:

**Features**:
- `initialize()` - Reads SUSHI.md from target on connection
- Caches SUSHI.md content for session
- Falls back to default minimal persona if SUSHI.md not found
- `profileSystem()` - Runs profiling script on target
- Extracts system identity (name/hostname) from SUSHI.md

**Data Classes**:
- `PersonaInitResult` - Result of initialization with SUSHI.md content
- `SystemProfile` - Structured system information from profiling

### 4. CommandSafety Classifier
**File**: `app/src/main/java/net/hlan/sushi/CommandSafety.kt`

Safety classifier with three-tier model:

**SafetyLevel.SAFE** (auto-execute):
- Read-only commands: `ls`, `cat`, `grep`, `pwd`, `df`, `free`, etc.
- Status checks: `systemctl status`, `journalctl`
- Raspberry Pi: `vcgencmd measure_temp`, `pinout`, `gpio readall`

**SafetyLevel.CONFIRM** (ask user first):
- Service operations: `systemctl restart`
- Package management: `apt install`
- File modifications: `rm`, `mv`, `chmod`
- Any `sudo` command

**SafetyLevel.BLOCKED** (never allowed):
- System shutdown: `reboot`, `shutdown`, `poweroff`, `halt`
- Destructive operations: `rm -rf /`, `mkfs`, `dd if=/dev/`
- Boot resource deletion: `rm -rf /boot`, `/etc`, `/bin`

### 5. Enhanced Gemini Clients
**Files**: 
- `app/src/main/java/net/hlan/sushi/GeminiClient.kt`
- `app/src/main/java/net/hlan/sushi/GeminiNanoClient.kt`

**New Methods**:
```kotlin
fun generateConversationalResponse(
    userMessage: String,
    sushiMdContext: String,
    conversationHistory: List<ConversationTurn>
): GeminiResult
```

**Features**:
- Accepts SUSHI.md context from target system
- Builds conversational prompts (first-person responses)
- Includes conversation history (last 5 turns)
- Supports both cloud (Gemini) and on-device (Nano)
- Parses EXECUTE: directives from LLM responses

**Prompt Format**:
```
[SUSHI.md content from target]

Recent conversation:
User: ...
System: ...

Current User Message: ...

Response Instructions:
Respond as the system in first person.
If executing commands, use: EXECUTE: <command>
```

### 6. ConversationTurn Data Class
**File**: `app/src/main/java/net/hlan/sushi/ConversationTurn.kt`

Represents a single conversation turn:
```kotlin
data class ConversationTurn(
    val timestamp: Long,
    val userMessage: String,
    val systemResponse: String,
    val commandExecuted: String?,
    val commandOutput: String?,
    val executionSuccess: Boolean
)
```

---

## SUSHI.md Structure

The persona configuration file on target systems:

```markdown
# Sushi AI Persona Configuration

## System Identity
- Hostname, hardware, OS, kernel, package manager

## Personality
- Professional Star Trek computer style
- First person responses
- Explain actions clearly

## Available Commands
### System Monitoring (safe)
- uptime, free, df, vcgencmd (Pi)

### Service Management
- systemctl status, journalctl

### File Operations
- ls, pwd, cat, grep

## Safety Guidelines
### App-Enforced (always blocked)
- shutdown, rm -rf /, mkfs, dd

### System-Specific (confirm first)
- Service restarts, apt operations, sudo

## System Purpose
*User edits this section*

## Health Monitoring
Normal parameters for temperature, memory, disk

## Conversation Logging
~/.sushi_logs/YYYY-MM-DD-HH_MM.log

## Notes
Installation date, version, user customizations
```

---

## File Structure on Target System

```
~/.config/sushi/
├── SUSHI.md                  # Main persona configuration
├── config.conf               # Custom settings (log path, etc.)
├── scripts/
│   └── profile_system.sh     # System profiling tool (JSON output)
└── knowledge/                # Optional: additional context files

~/.sushi_logs/                # Conversation logs
└── YYYY-MM-DD-HH_MM.log      # Timestamped session logs
```

---

## Remaining Work 🚧

### Phase 2: Conversation UI & Session Management

**Tasks**:
1. **Enhanced Gemini Dialog** - Transform from command generator to conversation interface
   - Chat bubble layout (user right, system left)
   - Voice + text input modes
   - Show command execution inline with spinner/checkmark
   - Expandable raw output sections
   
2. **Session-Based State Management**
   - Track conversation history per session
   - Maintain PersonaClient instance while connected
   - Clear state on disconnect, preserve on reconnect
   - Session start/end handling

3. **Command Execution Flow**
   - Parse LLM response for `EXECUTE:` directives
   - Classify with CommandSafety
   - Show confirmation dialog for CONFIRM level
   - Execute via SSH and capture output
   - Send result back to LLM for interpretation
   - Display final conversational response

4. **Conversation Logging on Target**
   - Write logs to `~/.sushi_logs/YYYY-MM-DD-HH_MM.log`
   - Read custom log path from `~/.config/sushi/config.conf`
   - Format: timestamp, user message, system response, commands executed

### Phase 3: Polish & Integration

**Tasks**:
1. **MainActivity Integration**
   - Initialize PersonaClient when terminal connects
   - Wire up enhanced Gemini dialog
   - Handle connection/disconnection events
   
2. **Settings & Preferences**
   - Toggle for persona mode vs raw terminal
   - Option to view/edit SUSHI.md remotely
   - Custom personality styles (future)

3. **Error Handling**
   - Graceful fallback if SUSHI.md missing
   - Reconnection logic with state preservation
   - Command execution timeouts

4. **Localization**
   - String resources for new UI elements
   - Translate for all 5 supported locales

5. **Documentation**
   - Update README.md
   - AGENTS.md updates
   - User guide for persona setup

6. **Testing**
   - Unit tests for CommandSafety
   - Integration tests for PersonaClient
   - Test on real Raspberry Pi hardware

---

## Design Decisions

Based on user requirements:

1. ✅ **Read SUSHI.md once on connect** (cached for session, refresh on reconnect)
2. ✅ **Fallback to minimal persona** if SUSHI.md not found (suggest running initialization)
3. ✅ **Enhanced Gemini dialog** for conversation (voice + text input)
4. ✅ **Auto-generated initial SUSHI.md**, iterate through usage
5. ✅ **System identity**: Connection alias/hostname first, then SUSHI.md override
6. ✅ **Two-tier safety**: App-enforced core rules + SUSHI.md system-specific rules
7. ✅ **Use `SUSHI.md` filename** (AI-optimized markdown)
8. ✅ **Logs in `~/.sushi_logs/`** with configurable custom path
9. ✅ **Session-based** with reconnect support (like terminal sessions)
10. ✅ **Raw terminal mode** → future features list (not in v0.5.0)

---

## Example Usage Flow

### First Time Setup

1. **Connect to Raspberry Pi** via Sushi app
2. **Run Play**: "Initialize AI Persona"
   - Installs framework on Pi
   - Generates SUSHI.md with auto-detected specs
3. **Optional**: SSH to Pi and edit `~/.config/sushi/SUSHI.md`
   - Add system purpose
   - Add important services
   - Add custom notes

### Conversational Interaction

1. **Connect** to Pi
2. **App reads** `~/.config/sushi/SUSHI.md`
3. **System identity** displayed: "Connected to raspberrypi"
4. **User (voice)**: "Computer, what's your temperature?"
5. **LLM** (with SUSHI.md context):
   ```
   Let me check my temperature.
   
   EXECUTE: vcgencmd measure_temp
   ```
6. **App**:
   - Classifies command → SAFE
   - Executes via SSH
   - Result: `temp=52.0'C`
   - Sends back to LLM
7. **LLM**: "I am running at 52 degrees Celsius. All systems nominal."
8. **Display** conversational response to user

---

## Technical Notes

### Performance Considerations
- SUSHI.md read once per connection (~2-5KB file, <100ms)
- Profiling script optional (not run every query)
- Conversation history limited to last 5 turns (keeps prompt size manageable)
- Command execution timeout: 30 seconds (configurable)

### Security
- SUSHI.md is read-only for app (user edits via SSH)
- Command safety enforced before execution
- No automatic shutdown/reboot commands
- SSH authentication unchanged (existing key/password)

### Extensibility
- SUSHI.md can be edited by user for custom instructions
- Additional `knowledge/` files can be referenced
- Profiling script extensible (add new metrics)
- Future: Model-specific files (CLAUDE.md, etc.)

---

## Next Steps

**Immediate**:
1. Review completed work
2. Test "Initialize AI Persona" Play on real Pi
3. Verify SUSHI.md generation and content

**Short-term** (Phase 2):
1. Build conversation UI
2. Implement command execution pipeline
3. Add session state management

**Medium-term** (Phase 3):
1. Integration with MainActivity
2. Testing and polish
3. Documentation updates
4. Release as v0.5.0

---

## Questions / Decisions Needed

None currently - all design questions resolved. Ready to proceed with Phase 2.

---

*Last updated: 2026-03-28*
*Author: OpenCode AI Assistant*
