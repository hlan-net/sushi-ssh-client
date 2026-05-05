# Sushi v0.5.0 - AI Conversation Release

**Release Date**: March 28, 2026  
**Version Code**: 15  
**Focus**: Conversational AI with Target Systems

## 🎉 Major New Feature: Talk to Your System

Sushi now lets you have natural conversations **with your connected Raspberry Pi or Linux server** using Gemini AI. This isn't just voice commands - it's a full conversational interface where the AI responds **as the system itself**.

### How It Works

1. **Connect** to your SSH host via Terminal
2. **Initialize** the AI persona using the "Initialize AI Persona" managed Play
3. **Open** the Gemini dialog and start talking to your system
4. **Ask** questions like "What's my CPU temperature?" or "Show me disk usage"
5. **Execute** commands safely with three-tier protection

### Key Features

#### 🤖 System Persona
- Persona lives on the **target system** in `~/.config/sushi/SUSHI.md`
- Star Trek computer-style responses ("I am running at 52°C")
- Fully customizable - edit SUSHI.md to change personality
- Auto-generated based on system profiling

#### 🛡️ Three-Tier Command Safety
- **SAFE**: Read-only commands auto-execute (`ls`, `pwd`, `uptime`, `vcgencmd measure_temp`)
- **CONFIRM**: Potentially dangerous commands require user approval (`sudo`, `rm`, `systemctl restart`)
- **BLOCKED**: Destructive commands are never allowed (`reboot`, `shutdown`, `rm -rf /`, `mkfs`)

#### 💬 Dual Input Modes
- **Text input**: Type your questions and commands
- **Voice input**: Use the existing voice button for hands-free interaction

#### 📝 Conversation Logging
- Full conversation history maintained in memory
- Logs saved to `~/.sushi_logs/` on the **target system**
- Timestamped entries with commands, outputs, and status

#### 🔌 Connection-First Architecture
- No persona until SSH connection established
- Transparent communication - your Android is just the interface
- SshConnectionHolder singleton shares connection between activities

### Supported AI Models

- **Gemini Cloud**: Flash (fast) or Pro (smarter) - requires API key
- **Gemini Nano**: On-device AI for supported devices - no API key needed

### Example Conversations

**User**: "What's my temperature?"  
**System**: "I am currently running at 52 degrees Celsius."

**User**: "Show me disk space"  
**System**: "I have 45GB free out of 120GB total on my main filesystem."

**User**: "Update the system"  
**System**: "This will run `sudo apt-get update`. This command requires confirmation. Do you want to proceed?"

## 🔧 Technical Implementation

### New Components

- **PersonaClient** (`PersonaClient.kt`) - Reads SUSHI.md from target system
- **ConversationManager** (`ConversationManager.kt`) - Orchestrates conversation flow and command execution
- **CommandSafety** (`CommandSafety.kt`) - Comprehensive command classification with regex patterns
- **SshConnectionHolder** (`SshConnectionHolder.kt`) - Singleton for sharing SSH state
- **ConversationTurn** (`ConversationTurn.kt`) - Data class for conversation history

### Target-Side Installation

New managed Play: **"Initialize AI Persona"**
- Creates `~/.config/sushi/` directory structure
- Generates default SUSHI.md persona
- Profiles system (hostname, OS, kernel, hardware)
- Sets up logging directory

### Integration Points

- Enhanced `GeminiClient` and `GeminiNanoClient` with conversational methods
- Updated `MainActivity` with ~260 lines of conversation management
- Updated `TerminalActivity` to track connection state
- Enhanced Gemini dialog UI with text input and send button

## 🌍 Internationalization

All new UI strings translated to 5 languages:
- English (en)
- German (de)
- Finnish (fi)
- Swedish (sv)
- Spanish (es)

## 🧪 Testing

Comprehensive instrumented test suite (`AiConversationTest.kt`) with 16 tests covering:
- Command safety classification (safe, confirm, blocked)
- Conversation data models
- Component integration
- Raspberry Pi-specific commands
- Piped and compound command handling

**Test Results**: 8/8 core tests passing on Nokia G42 5G (Android 15)

## 📦 Installation

### Requirements
- Android 8.0 (API 26) or higher
- SSH access to a Linux system
- (Optional) Gemini API key for cloud AI
- (Optional) Device with Gemini Nano support for on-device AI

### First-Time Setup

1. Install Sushi v0.5.0 APK
2. Configure your SSH connection in Settings
3. Connect to your host via Terminal
4. Run "Initialize AI Persona" from Plays tab
5. Open Gemini dialog and start conversing!

### Upgrading from v0.4.x

- No data migration needed
- Existing SSH hosts and settings preserved
- New conversation feature available immediately after SSH connection

## 🐛 Known Limitations

- Conversation only available after SSH connection
- Command execution requires active SSH session
- Long-running commands may timeout (configurable)
- Release APK builds are slow on ARM64 systems (use debug APK for now)

## 🔮 Future Enhancements

See `FUTURE_FEATURES.md` for planned improvements:
- Enhanced chat UI with message bubbles
- Command output streaming
- Multi-system conversations
- Conversation search and export
- Voice-only mode (no screen required)

## 👥 For Internal Testers

### What to Test

1. **Basic Conversation**:
   - Text input: "What's my hostname?"
   - Voice input: "Show me memory usage"
   - Verify responses make sense

2. **Command Safety**:
   - Try SAFE command: "list files"
   - Try CONFIRM command: "restart nginx" (should show dialog)
   - Try BLOCKED command: "reboot" (should be rejected)

3. **Persona Customization**:
   - SSH to your device
   - Edit `~/.config/sushi/SUSHI.md`
   - Reconnect and test new personality

4. **Edge Cases**:
   - Disconnect during conversation
   - Switch between text and voice input
   - Try complex commands
   - Test in different languages

### Feedback Welcome

Please report:
- Unexpected command classifications
- Confusing AI responses
- UI/UX issues
- Crashes or errors
- Translation issues

## 📊 Statistics

- **Files Created**: 9 new source files
- **Files Modified**: 15 existing files
- **Lines Added**: ~2,280 lines (code + docs)
- **Languages**: 5 fully localized
- **Test Coverage**: 16 automated tests
- **Development Time**: ~2 days

## 🙏 Acknowledgments

Special thanks to:
- QEMU team for ARM64 x86_64 emulation
- Android team for Gemini Nano integration
- Beta testers in Espoo, Finland

## 🔗 Links

- **GitHub**: https://github.com/hlan-net/sushi
- **Documentation**: See `TESTING_GUIDE.md`, `AI_PERSONA_IMPLEMENTATION_STATUS.md`
- **Changelog**: See `CHANGELOG.md` for full details

---

**Internal Testing Build**  
Build Date: March 28, 2026  
Build Type: Debug (unsigned)  
APK Size: 17 MB  
Minimum SDK: 26 (Android 8.0)  
Target SDK: 36 (Android 15)
