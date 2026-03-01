# Changelog

All notable changes to this project are documented in this file.

The format is based on Keep a Changelog and follows semantic versioning.

## [0.3.0] - Unreleased

### Changed
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
