# Future Features & Enhancements

Ideas and features to watch for future consideration.

## Conversational AI

### Raw Terminal Mode Toggle
**Status**: Deferred from v0.5.0  
**Priority**: Medium  
**Description**: Toggle between conversational AI mode and raw terminal command input.

**Use Cases**:
- Advanced users who want direct shell access
- Debugging/troubleshooting when AI responses aren't helpful
- Learning mode (see actual commands vs conversational)

**Implementation Ideas**:
- Settings toggle: "Conversation Mode" vs "Raw Mode"
- Quick switch button in UI
- In Raw Mode: direct command input, no LLM processing
- Hybrid Mode: Show both AI response and raw command

---

## AI Persona Enhancements

### Multi-Model Support (CLAUDE.md, etc.)
**Status**: Architecture supports it  
**Priority**: Low  
**Description**: Support model-specific optimization files on target system.

**Files**:
- `SUSHI.md` - General persona (all models)
- `GEMINI.md` - Gemini-specific optimizations
- `CLAUDE.md` - Claude-specific optimizations
- `GPT.md` - OpenAI GPT-specific

**Use Cases**:
- Different models have different strengths
- Model-specific prompt engineering
- User might switch between models

---

### Context-Aware File Operations
**Status**: Not started  
**Priority**: Medium  
**Description**: Voice-controlled file browsing, downloading, editing.

**Features**:
- "Download /var/log/syslog to my phone"
- "Show me files in /home/pi/projects"
- "Edit config.txt" → Opens in-app editor
- Visual file browser overlay

**Implementation**:
- SFTP download capability (SshClient already has upload)
- In-app text file viewer/editor
- Voice intent detection for file operations

---

### Proactive Monitoring
**Status**: Not started  
**Priority**: Low  
**Description**: System proactively alerts user to issues.

**Examples**:
- "I notice my temperature has been above 70°C for 10 minutes"
- "Disk space is at 95%, may need attention"
- "The web server has been down for 2 hours"

**Implementation**:
- Background monitoring script on target
- Alerts stored in shared file
- App polls or Pi pushes notifications
- User toggles in settings

---

### LLM-Generated Skills
**Status**: Concept only  
**Priority**: Low (security concerns)  
**Description**: User asks system to "learn" new capabilities.

**Example**:
- User: "Learn how to monitor my Python app"
- LLM: Generates monitoring script
- Saves to `~/.config/sushi/scripts/monitor_python_app.sh`
- Adds to SUSHI.md available commands

**Concerns**:
- Security risk (auto-generated code)
- Needs sandboxing or review process
- Could be powerful but dangerous

---

## Voice & Input

### Wake Word Support
**Status**: Not started  
**Priority**: Low  
**Description**: "Computer, ..." trigger phrase for voice input.

**Implementation**:
- Android speech recognition API
- Listen for "Computer" keyword
- Auto-start voice recording
- Optional: Other wake words (custom)

---

### Voice Output (TTS)
**Status**: Not started  
**Priority**: Low  
**Description**: System speaks responses instead of just displaying text.

**Features**:
- Android Text-to-Speech API
- Configurable voice (male/female, accent)
- Toggle in settings (on/off)
- Real Star Trek computer experience

**Use Cases**:
- Hands-free operation
- Accessibility
- Immersive experience

---

### Text Input in Conversation
**Status**: Partially implemented (dialog supports it)  
**Priority**: High  
**Description**: Type messages instead of voice only.

**Current**: Voice button only  
**Future**: Text input field + voice button

**Features**:
- Keyboard input for queries
- Auto-complete for common commands
- Command history (up arrow)
- Mix voice and text in same conversation

---

## System Integration

### Multiple Pi Support / Shared Knowledge
**Status**: Architecture supports independent personas  
**Priority**: Medium  
**Description**: Manage multiple Pis with distinct or shared personas.

**Options**:
1. **Independent** (current): Each Pi has its own SUSHI.md
2. **Shared Knowledge**: Common knowledge base, system-specific overrides
3. **Unified AI**: One persona aware of all your systems

**Use Cases**:
- Home lab with multiple Pis
- Dev/staging/prod environments
- IoT device fleet

---

### Auto-Update Persona
**Status**: Not started  
**Priority**: Low  
**Description**: Persona framework auto-updates on target.

**Features**:
- Check for updates on connection
- Download new profiling scripts
- Update SUSHI.md template (preserve user content)
- Version tracking

**Implementation**:
- GitHub releases or app server
- `~/.config/sushi/version.txt`
- Smart merge (don't overwrite user edits)

---

### Custom Log Location
**Status**: Supported in config.conf, not enforced yet  
**Priority**: Medium  
**Description**: User specifies custom log directory.

**Current**: Logs always go to `~/.sushi_logs/`  
**Future**: Read `log_dir` from `~/.config/sushi/config.conf`

**Use Cases**:
- Mount point for external storage
- Network share
- RAM disk for performance

---

## Raspberry Pi Specific

### GPIO Voice Control
**Status**: Not started  
**Priority**: Low  
**Description**: Voice commands for GPIO pin control.

**Examples**:
- "Turn on LED on pin 17"
- "Read sensor on pin 23"
- "Toggle relay on pin 22"

**Implementation**:
- GPIO commands in SUSHI.md
- Safety rules (confirm before toggling)
- Pin mapping in knowledge/gpio_config.conf

---

### Camera Integration
**Status**: Not started  
**Priority**: Low  
**Description**: Voice-controlled Pi camera operations.

**Examples**:
- "Take a photo"
- "Show me the last picture"
- "Start recording video"

**Implementation**:
- raspistill/raspivid commands
- Download images to phone
- In-app image viewer

---

### Home Assistant Integration
**Status**: Not started  
**Priority**: Medium  
**Description**: If Pi runs Home Assistant, control it via voice.

**Examples**:
- "Turn off living room lights"
- "What's the temperature in bedroom?"
- "Lock the front door"

**Implementation**:
- Detect Home Assistant in SUSHI.md
- Include HA REST API commands
- Authentication (HA API key)

---

## Developer Experience

### Remote SUSHI.md Editor
**Status**: Not started  
**Priority**: Medium  
**Description**: Edit SUSHI.md from Android app without SSH.

**Features**:
- Read SUSHI.md via SSH
- Display in editable text area
- Syntax highlighting (markdown)
- Save back to target
- Validation before save

**Implementation**:
- New activity: SushiMdEditorActivity
- SFTP or SSH cat/echo for read/write
- Confirm before overwrite

---

### Conversation Export
**Status**: Not started  
**Priority**: Low  
**Description**: Export conversation logs for review/debugging.

**Features**:
- Download logs from `~/.sushi_logs/` to phone
- Export as text, JSON, or markdown
- Share via Android Share sheet
- Search/filter logs

---

### Persona Templates
**Status**: Not started  
**Priority**: Low  
**Description**: Pre-made SUSHI.md templates for common setups.

**Templates**:
- Home Server (media, automation)
- Development Environment (languages, tools)
- IoT Device (sensors, GPIO)
- Web Server (nginx, databases)
- Retro Gaming (RetroPie specific)

**Implementation**:
- Template library in app or online
- "Load Template" button in settings
- Merge with existing SUSHI.md

---

## User Experience

### First-Run Tutorial
**Status**: Not started  
**Priority**: Medium  
**Description**: Guide new users through persona setup.

**Flow**:
1. Connect to Pi for first time
2. Detect no SUSHI.md
3. Show tutorial overlay
4. Guide through running "Initialize AI Persona"
5. Explain how to use conversation mode

---

### Visual System Dashboard
**Status**: Not started  
**Priority**: Low  
**Description**: Graphical view of system stats.

**Features**:
- Real-time CPU/memory/temp graphs
- Service status indicators
- Disk usage pie chart
- Network traffic graph

**Integration**:
- Data from profiling script
- Update every 5-10 seconds
- Accessible from conversation or separate tab

---

### Command History
**Status**: Not started  
**Priority**: Medium  
**Description**: Track and reuse past commands.

**Features**:
- Store all executed commands locally
- Search/filter history
- Quick re-execute
- Suggest commands based on current context

**Implementation**:
- SQLite database
- Integrated with conversation UI
- "History" button to browse

---

### Conversation Branching
**Status**: Concept only  
**Priority**: Low  
**Description**: Fork conversation to explore alternatives.

**Example**:
- Main conversation: "Check nginx status"
- Branch: "What if I restart it?"
- LLM simulates without executing
- Merge back or discard branch

**Use Cases**:
- What-if scenarios
- Learning/training mode
- Risk-free exploration

---

## Advanced Features

### Multi-Hop SSH
**Status**: App already supports jump servers  
**Priority**: Low  
**Description**: Persona through jump server/bastion host.

**Current**: Jump server support exists for SSH  
**Future**: Ensure persona initialization works through jump

---

### Collaborative Personas
**Status**: Concept only  
**Priority**: Very Low  
**Description**: Multiple users share same persona, conversation merges.

**Use Cases**:
- Team managing shared server
- Family home server
- Multi-user development box

**Challenges**:
- Conflict resolution
- Privacy/security
- Conversation attribution

---

### AI-Powered Troubleshooting
**Status**: Concept only  
**Priority**: Medium  
**Description**: System diagnoses and fixes issues autonomously.

**Example**:
- User: "My web server isn't working"
- AI: Checks service status → stopped
- AI: Checks logs → finds error
- AI: Suggests fix → restarts service
- AI: Verifies fix → confirms working

**Implementation**:
- Structured troubleshooting flows
- Decision trees in SUSHI.md
- Root cause analysis from logs
- Safe automatic fixes (with confirmation)

---

## Tracking

**Next Review**: After v0.5.0 release  
**Priority Legend**:
- High: Should be in next major version
- Medium: Consider for future release
- Low: Nice to have, low urgency
- Very Low: Interesting idea, far future

---

*This is a living document. Add ideas here as they come up.*  
*Last updated: 2026-03-28*
