# Changelog

All notable changes to this project are documented in this file.

The format is based on Keep a Changelog and follows semantic versioning.

## [0.2.0] - Unreleased

### Planned
- Simplify the main UI layout to reduce visual density and improve focus on core SSH actions.

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
