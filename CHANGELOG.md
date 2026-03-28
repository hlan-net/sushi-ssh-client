# Changelog

All notable changes to this project are documented in this file.

The format is based on Keep a Changelog and follows semantic versioning.

## [0.5.0] - 2026-03-28

### Added
- **Conversational AI with target system**: Talk directly to your connected Raspberry Pi/Linux system using Gemini.
  - System persona configured via `~/.config/sushi/SUSHI.md` on target (auto-generated on first use).
  - Star Trek computer-style responses where the system responds in first person.
  - Three-tier command safety model: SAFE (auto-execute), CONFIRM (ask user first), BLOCKED (never allow).
  - Support for both text and voice input in conversation dialog.
  - Conversation logs saved to `~/.sushi_logs/` on target system with timestamps.
  - Works with both Gemini Cloud (Flash/Pro) and on-device Gemini Nano.
- Added `PersonaClient` for reading and managing SUSHI.md persona configuration from target.
- Added `CommandSafety` classifier with comprehensive regex patterns for safe/confirm/blocked commands.
- Added `ConversationManager` to orchestrate AI conversation flow, command execution, and safety checks.
- Added `SshConnectionHolder` singleton to share SSH connection state between Terminal and Main activities.
- Added `ConversationTurn` data class for conversation history tracking.
- Added "Initialize AI Persona" managed Play to install persona framework on target systems.
- Added conversational response methods to `GeminiClient` and `GeminiNanoClient`.
- Added conversation UI elements in Gemini dialog: text input field, send button, IME action support.
- Added 9 new conversation-related strings with full localization (de, fi, sv, es).

### Changed
- Enhanced Gemini dialog to support dual input modes: voice (existing) and text (new).
- Updated `TerminalActivity` to track connection state via `SshConnectionHolder`.
- Updated `MainActivity` with ~260 lines of conversation integration logic.
- Conversation only becomes available after SSH connection is established (connection-first architecture).

## [0.4.3] - 2026-03-27

### Fixed
- Fixed terminal mode duplicate virtual keyboard input where short commands could be sent twice (for example `cd` becoming `cdCD`).

### Changed
- Updated terminal IME flags to reduce transformed/replayed text commits from virtual keyboards.

## [0.4.2] - 2026-03-06

### Fixed
- Fixed connection dropping when the soft keyboard opened/closed by moving PTY resize off the UI thread.
- Improved SSH session stability with `xterm` PTY type and more frequent keepalive.
- Improved disconnect detection by triggering connection-closed handling directly from shell reader callbacks.

### Added
- Added `connectionStaysAliveAfterTerminalUiConnect` instrumented test for keyboard open/close stability.

## [0.4.1] - 2026-03-05

### Fixed
- Fixed crash from coroutine binary incompatibility by pinning `kotlinx-coroutines` to `1.9.0` for ML Kit compatibility.

### Changed
- Migrated to AGP `9.0.1` and Gradle `9.1.0`.
- Improved Espresso device QA stability by disabling autofill overlays and adding focus retry handling.

## [0.4.0] - 2026-03-04

### Added
- Added Gemini Nano on-device inference via ML Kit GenAI Prompt API (no API key or network required for inference on supported devices).
- Added user-initiated Gemini Nano model download and status display in Settings.
- Added Nano preference toggle: when enabled and Nano is available, inference is routed on-device; otherwise falls back to cloud.
- Added cloud model selector (Flash / Pro) in Gemini settings, defaulting to `gemini-2.5-pro`.
- Added Android Share target: Sushi now appears in the system Share sheet for text and files, uploading via SFTP to a selected host.
- Added Gemini conversation transcript: the Gemini dialog now shows a scrollable chat history of all prompts and responses in the session.
- Added shared phrase picker helper to eliminate code duplication across activities.
- Added Material Design icons across all screens and dark mode color scheme.

### Changed
- Bumped `minSdk` from 24 to 26 (required by ML Kit Prompt API; drops Android 7 support).
- Updated translations for all new strings in de, fi, sv, es locales.
- Bumped app version to `0.4.0` (`versionCode` 13).

## [0.3.0] - 2026-03-03

### Added
- Added terminal font size setting (Small/Medium/Large/XL) in Settings > General.
- Added Drive log upload on terminal disconnect (automatic when always-save is enabled).
- Added copy-command button to Gemini dialog.
- Added Gemini interaction logging to the console log (prompt and generated command recorded for each voice request).
- Added settings button and return-to-terminal button on main screen.

### Fixed
- Fixed SSH session being destroyed on screen rotation (orientation changes no longer disconnect).
- Fixed Drive uploads landing in Drive root — all logs now go to a `sushi-logs/` folder (created automatically).
- Fixed Drive upload errors showing only a generic message — actual error detail is now shown.
- Fixed console log (plays/Gemini activity) never being uploaded to Drive — it is now uploaded after each play run.
- Added ProGuard keep rule for `ListAdapter.getCurrentList()` stripped in minified builds.

### Changed
- Redesigned main screen and Settings into tabbed carousel layouts.
- Unified Google Sign-In: Drive auth now requests both Drive and Gemini scopes; Gemini supports OAuth2 bearer token with API key fallback.
- Migrated Gemini API calls to coroutines with HTTP timeouts (15s connect / 30s read).
- Added translations for new strings in all 5 locales (en, fi, sv, de, es).
- Bumped development version target to `0.3.0-dev` (`versionCode` 11).

## [0.2.1] - 2026-03-01

### Fixed
- Fixed startup crash on some devices (including Pixel) caused by legacy host configs with missing `authPreference`.
- Hardened terminal text rendering fallback to avoid startup failures from malformed cached output.
- Improved terminal output line-ending behavior to avoid extra/missing newlines in interactive command output.

### Changed
- Bumped app version to `0.2.1` (`versionCode` 10).

## [0.2.0] - 2026-03-01

### Added
- Added a collapsible session tools section in the main screen to keep secondary actions hidden by default.
- Added a Gemini control modal so voice interaction and Gemini output no longer occupy permanent space on the main screen.
- Added tabbed sections in Settings (General, Gemini, Drive) to reduce vertical clutter.
- Added a dedicated Terminal mode screen for direct shell interaction with live input, Enter/Tab/Backspace, and Ctrl+C/Ctrl+D controls.
- Added a Plays system with managed predefined plays and per-run host selection.
- Added host-level SSH authentication preference (auto/password-only/key-only).
- Added jump-server support with host-list selection and dependent host cleanup.
- Added settings quick actions, connection testing, and copyable diagnostics.
- Added local SSH test wizard defaults with optional jump-server credentials.

### Changed
- Simplified the main UI by replacing inline Gemini details with a compact status and modal entrypoint.
- Switched main flow to terminal-first session startup.
- Improved terminal rendering/input behavior and reconnect handling.
- Bumped app version to `0.2.0` (`versionCode` 8).

## [0.1.3] - 2026-02-27

### Added
- Added phrase validation for required fields and duplicate-name prevention in phrase create/edit flows.
- Added phrase import upsert behavior by phrase name to avoid duplicate entries during JSON imports.
- Added managed key phrases created from key generation:
  - `Install SSH Key`
  - `Remove Sushi SSH Keys`
- Added timestamped SSH public key comments in generated keys:
  - `Sushi - SSH client key yyyy-MM-dd HH:mm`
- Added device QA coverage for managed key phrase creation and selection.
- Added SSH integration coverage for multi-command execution and response verification.

### Changed
- Updated phrase delete confirmation to include the selected phrase name and destructive-action warning.
- Bumped app version to `0.1.3` (`versionCode` 7).

## [0.1.2] - 2026-02-26

### Changed
- Maintenance and integration updates for local SSH testing and QA automation.
