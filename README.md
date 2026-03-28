# Sushi - SSH Client

An open source Android SSH client focused on fast connections, clean session management, and a modern UI.

## Status
- Active development with working SSH session flow and host management.
- **NEW**: Conversational AI mode - talk directly to your connected system using Gemini.
- Optional Gemini voice command mode (user-provided API key).
- Optional Google Drive log uploads (Google sign-in required).

## v0.5.0 highlights
- **Conversational AI with your target system**:
  - Talk directly TO your connected Raspberry Pi/Linux system via Gemini
  - System persona configured via `~/.config/sushi/SUSHI.md` on target
  - Star Trek computer-style responses ("I am running at 52°C")
  - Three-tier command safety: SAFE (auto-execute), CONFIRM (ask first), BLOCKED (never allow)
  - Support for both text and voice input
  - Conversation logs saved to `~/.sushi_logs/` on target system
  - One-click persona initialization via managed Play
  - Works with both Gemini Cloud and on-device Gemini Nano
- Enhanced connection architecture:
  - `SshConnectionHolder` singleton shares connection state between activities
  - Conversation only available after SSH connection established
- Target-side logging for AI conversations

## v0.3.0 highlights
- Main screen reorganization:
  - shared terminal session block stays visible at the top
  - top carousel now uses focused `Terminal` and `Plays` tabs
  - Gemini controls moved into the `Terminal` tab
  - Plays tab now combines play actions and session logs in one flow
- Settings redesign with carousel navigation:
  - dedicated pages for `General`, `SSH`, `Gemini`, and `Drive`
  - `Hosts` and `Keys` grouped into their own SSH-focused settings page
  - improved settings tab state and section grouping
- Theme support:
  - `Auto` (follow system), `Light`, and `Dark` appearance modes
- QoL updates:
  - smarter pending-save behavior for Gemini API key changes
  - main/settings tab memory across app restarts
  - one-command Wi-Fi deploy helper script (`scripts/install-wifi-debug.sh`)

## v0.1.3 highlights
- Phrase management improvements:
  - validation for empty and duplicate phrase names
  - safer JSON import (upsert by phrase name instead of duplicate inserts)
  - clearer delete confirmation for phrases
- Key workflow now creates two managed phrases:
  - `Install SSH Key`
  - `Remove Sushi SSH Keys`

## Development
Prerequisites:
- Android Studio (Hedgehog or newer recommended)
- JDK 17

Optional integrations:
- Gemini voice mode: add your API key in app settings.
- Google Drive logs: create an OAuth client for the package `net.hlan.sushi` and enable the Drive API.

Build a debug APK:
```bash
./gradlew assembleDebug
```

Build and install to a Wi-Fi device in one step (auto-increments debug versionCode to avoid downgrade errors):
```bash
./scripts/install-wifi-debug.sh
```

Optional: pass a specific ADB device target:
```bash
./scripts/install-wifi-debug.sh 192.168.1.136:43333
```

Run the optional local SSH integration tests on a connected device (not for CI):

1) Create a local git-ignored config via interactive wizard:
```bash
./scripts/setup-local-ssh-test.sh
```

The wizard is iterative: it reuses values from `.local/local-ssh-test.env` as defaults,
including optional jump-server fields.

This writes secrets to `.local/local-ssh-test.env` (chmod 600, git-ignored).

2) Run the tests:
```bash
./scripts/run-local-ssh-test.sh
```

You can still bypass the file and pass values as environment variables when needed.

Run the comprehensive non-external device QA tap-through suite:
```bash
./scripts/run-device-qa-suite.sh
```

This suite runs instrumented coverage for:
- app launch smoke checks
- JSch runtime sanity
- full non-external UI tap-through (settings, host management, keys, about, phrases)

If credentials are not set, `LocalSshIntegrationTest` is skipped (JUnit assumption), not failed.

You can still run Gradle directly if needed:
```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.hlan.sushi.LocalSshIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.sshHost=YOUR_HOST \
  -Pandroid.testInstrumentationRunnerArguments.sshPort=22 \
  -Pandroid.testInstrumentationRunnerArguments.sshUsername=YOUR_USER \
  -Pandroid.testInstrumentationRunnerArguments.sshPassword=YOUR_PASSWORD \
  -Pandroid.testInstrumentationRunnerArguments.sshJumpEnabled=true \
  -Pandroid.testInstrumentationRunnerArguments.sshJumpHost=YOUR_JUMP_HOST \
  -Pandroid.testInstrumentationRunnerArguments.sshJumpPort=22 \
  -Pandroid.testInstrumentationRunnerArguments.sshJumpUsername=YOUR_JUMP_USER
```

Local checks before push:
```bash
./scripts/install-git-hooks.sh
```
This installs a pre-push hook that runs `./gradlew testDebugUnitTest`. To skip once: `SKIP_PRE_PUSH_TESTS=1 git push`.

If you do not have the Gradle wrapper JAR yet, generate it once with:
```bash
gradle wrapper
```

## License
Apache-2.0. See `LICENSE`.
