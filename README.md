# Sushi - SSH Client

An open source Android SSH client focused on fast connections, clean session management, and a modern UI.

## Status
- Active development with working SSH & local shell session flow, host management, and automated Plays.
- **Conversational AI mode**: Talk directly to your connected system using Gemini Cloud or on-device Gemini Nano.
- **Security**: Host key verification (TOFU), encrypted private keys with passphrase support, and AES256-GCM encrypted credential storage (`SecurePrefs`).
- **Connection reliability**: Foreground service keep-alive (`SshConnectionService`) and TCP keep-alive probes prevent background drops.
- **Authentication**: Modern Android Credential Manager & Identity Authorization for Google Drive log backups; GitHub OAuth Device Flow for in-app feedback.

## Features

### 💻 SSH & Terminal
- Fast interactive terminal with custom `TerminalView`, ANSI/OSC escape handling, and UTF-8 support.
- Local shell backend (`LocalShellBackend`) alongside remote SSH (`SshClient`).
- Trust-On-First-Use (TOFU) host key verification with fingerprint verification, mismatch warnings, and `HostKeysActivity` management.
- Password, unencrypted private key, and encrypted private key (PEM / OpenSSH bcrypt) authentication.
- Jump server (bastion host / proxy) support.
- Phrase management with quick-execution and automated key deployment phrases.
- Parameterized automated Plays with live preview and description support.
- Foreground service keep-alive ensuring sessions survive app backgrounding, switching apps, and 2FA prompts.

### 🤖 Conversational AI with Target System
- Talk directly TO your connected Linux system / Raspberry Pi via Gemini (Cloud or Nano).
- Target-side persona (`~/.config/sushi/SUSHI.md`) automatically created on first use.
- Three-tier command safety model: `SAFE` (auto-execute), `CONFIRM` (ask user first), and `BLOCKED` (never execute).
- Live command output streaming (`onChunk`) and raw terminal bypass mode.
- SQLite-backed transcript persistence and interactive session history browser (`GeminiHistoryActivity`).
- Dual voice and text input.

### 🎨 Customization & Convenience
- Light, Dark, and System theme support with customizable accent color palette (Gari amber, Wasabi, Coral, Terracotta).
- First-run setup checklist guiding SSH host setup, key generation, and optional cloud integrations.
- In-app feedback system filing issues directly via GitHub Device Flow.
- Google Drive session log backups via modern Credential Manager.

## Recent Release Highlights

### v0.8.3
- **Light Mode**: The terminal follows the system theme instead of a fixed dark screen. In light mode its key labels had been drawn at 1.04:1 against their own background — present, but invisible. The ANSI palette came with it, since the raw colour constants suit a dark terminal and put yellow and white at about 1.1:1 on a light one.
- **Readable ANSI Backgrounds**: `ESC[40m`–`ESC[47m` had reused the foreground palette, so a highlight painted text in its own colour — a blank rectangle at exactly 1.00:1. Backgrounds are now their own palette per theme, and a foreground that would fall short against one is shifted toward white or black until it reads.
- **No More Escape Codes at the Prompt**: The terminal filtered only two kinds of escape sequence and let the rest through with their payload visible, so a prompt arrived as `=(B larry@edge:~ $`. Every escape shape is now recognised and consumed.
- **Visible Gemini Responses**: The response bubble drew its text in the terminal's colour, which in dark mode was its own background colour. It now reads at about 16:1 in both themes.

### v0.8.2
- **Command History from the Terminal**: Up and Down buttons send the cursor sequences to the shell, so `history` is reachable from the phone. A hardware keyboard's arrows work too — neither reached the shell before.
- **Readable Status Lines**: The terminal's own messages no longer run into each other or into the shell prompt; the same fix applies to the Plays session log.

### v0.8.1
- **Jump Server Fix**: Connecting through a bastion now works when the two hosts use different login methods. The jump host authenticates on its own preference instead of inheriting the target's, which had left a key-auth target's bastion with no password to offer and failed the connection outright.
- **Per-Host Authentication Methods**: Each connection now offers only the methods its own host is configured for, so a host set to Password no longer authenticates with the key instead.

### v0.8.0
- **Command History**: Every command Sushi runs — AI-issued, Raw Terminal Mode, or a Play — is stored locally in SQLite and browsable from the Terminal tab, with search, per-host filter, and copy / delete / re-run. Capped at 500 entries per host; a Play that substituted a secret parameter *value* into its command is not recorded at all, since the rendered command would hold that value in plaintext.
- **AI-Powered Troubleshooting**: The conversation chains diagnosis automatically (command → output → interpretation → next command) until it reaches a conclusion, with every step still classified by `CommandSafety` and a switch in the Gemini dialog to turn it off.
- **Multi-System Awareness**: The AI prompt carries an Infrastructure section describing your other saved hosts, so cross-system questions can be answered without connecting; the Gemini dialog names the active host.
- **Complete Localization**: Finnish, German, Swedish, and Spanish translations for all 508 translatable strings.
- **Crash Fixes**: Command execution on Android 12L and older, and feedback with device info on Android 8.x.

### v0.7.12
- **Remote Persona Editor**: Read and edit the target's `~/.config/sushi/SUSHI.md` from Settings → Gemini → Edit persona, with save validation, overwrite confirmation, and a "Reset to default" option.
- **SFTP Download**: A "Download file" action on the terminal tab pulls a remote file to the phone and offers Open/Share.
- **Custom Log Location**: Honours the `log_dir` key in `~/.config/sushi/config.conf` for conversation logs.
- **GitHub Sign-In Reliability**: Device-flow login no longer fails on a transient network hiccup while authorizing in the browser.

### v0.7.11
- **SSH Host Key Verification (TOFU)**: Fingerprint confirmation dialog on first connect, host key mismatch alerts, and host key manager screen.
- **Encrypted SSH Key Passphrase Support**: Support for passphrase-protected private keys (legacy PEM & OpenSSH bcrypt formats) with session passphrase caching.
- **Credential Manager Migration**: Migrated Google Drive authorization from legacy `GoogleSignIn` to Android `CredentialManager` and Google Identity `AuthorizationClient`.
- **Infrastructure & Dependencies**: AGP 9.4.0, Gradle 9.7.1, JSch 2.28.7, GoogleId 1.2.0, Google API client 2.9.1, and AppCompat 1.8.0.

### v0.7.0 – v0.7.10
- **Background Keep-Alive**: Foreground service (`SshConnectionService`) and keep-alive packets keep SSH sessions alive when the app is backgrounded.
- **Accent Color Picker**: Customizable primary color (Gari amber, Wasabi, Coral, Terracotta) with instant whole-app theme application.
- **In-App Feedback**: GitHub Device Flow authentication to submit feedback issues directly from settings.

### v0.6.0
- **Terminal Backend Abstraction**: `TerminalBackend` decoupling SSH and local shell execution.
- **Gemini Transcript Persistence**: SQLite storage for conversational turns, command outputs, and history browser.
- **Connection Error Classification**: Actionable error banners with typed `ConnectFailure` reasons.
- **First-Run Checklist**: Persistent onboarding card for new setups.
- **Raw Terminal Mode & Streaming**: Direct shell toggle in Gemini dialog and incremental output streaming.

## Development
Prerequisites:
- Android Studio (Hedgehog or newer recommended)
- JDK 17

Android SDK/NDK setup (machine-specific):
- `local.properties` is gitignored and must be set per machine:
  ```properties
  sdk.dir=/absolute/path/to/your/Android/sdk
  ```
- This project requires these SDK components:
  ```bash
  sdkmanager \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "ndk;27.0.12077973" \
    "cmake;3.22.1"
  ```
- Quick verification:
  ```bash
  ./gradlew assembleDebug
  ./gradlew assembleMinifiedDebug
  ```

If native build setup fails on Linux hosts:
- The NDK toolchain binaries under `ndk/27.0.12077973/toolchains/llvm/prebuilt/...` must run on your host.
- Validate directly:
  ```bash
  "$ANDROID_SDK_ROOT/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version
  ```
- If that command fails due to missing shared libraries, install the required compatibility libs for your distro/architecture, then retry.

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
