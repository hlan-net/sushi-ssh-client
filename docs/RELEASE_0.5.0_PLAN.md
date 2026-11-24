# v0.5.0 Release Plan (Draft)

This document captures suggested content for the `0.5.0` release.
It is a planning artifact, not a committed changelog entry.

## Release intent

Make terminal sessions more trustworthy and maintainable by improving SSH security posture, connection resilience, and test coverage while reducing implementation complexity.

## Proposed scope

Detailed Gemini-specific scope and user flows are tracked in `docs/GEMINI_0.5.0_PLAN.md`.
GitHub execution tracking template is in `docs/GITHUB_MILESTONE_0.5.0.md`.

### 1) SSH security hardening (P0)
- Add host key trust-on-first-use (TOFU) flow with fingerprint prompt on first connect.
- Persist trusted host fingerprints and block/warn on fingerprint mismatch.
- Add explicit user actions for mismatch cases (cancel, trust new key, replace trusted key).
- Ensure sensitive values are never logged during host-key and auth error handling.

### 2) Connection reliability and UX feedback (P0)
- Add visible terminal connection-state banner (`Connecting`, `Connected`, `Reconnecting`, `Disconnected`).
- Add explicit reconnect affordance after disconnect.
- Improve disconnect and timeout messaging to show actionable, user-friendly causes.
- Audit keyboard/session lifecycle paths to avoid regressions from IME and orientation changes.

### 3) Threading and error-handling consistency (P1)
- Standardize async/background logic on coroutines and structured concurrency.
- Reduce mixed `Thread { ... }` and `runOnUiThread { ... }` pathways where feasible.
- Introduce a consistent error-to-UI mapping approach for SSH/network/storage boundaries.
- Keep UI updates on main thread with clear lifecycle-safe cancellation behavior.

### 4) Test coverage expansion (P1)
- Add regression coverage for terminal input edge cases (duplicate commits, composition behavior).
- Add SSH handshake/algorithm-negotiation coverage for key compatibility scenarios.
- Add focused tests for disconnect/reconnect flows and session state restoration.
- Keep tests deterministic and avoid live-network dependency in CI.

### 5) Developer and release workflow improvements (P2)
- Add a release checklist for version bump, changelog updates, build validation, and artifact verification.
- Strengthen CI gates for release candidates (lint, unit tests, key instrumentation suite as available).
- Document branch/PR expectations for release-targeted changes.
- Improve diagnostics in CI logs for faster failure triage.

## Candidate changelog draft for 0.5.0

Use this as a starting point when `CHANGELOG.md` is updated at release time.

```markdown
## [0.5.0] - YYYY-MM-DD

### Added
- Added SSH host key trust-on-first-use with fingerprint confirmation on first connection.
- Added trusted host key persistence and mismatch warning/decision flow.
- Added terminal connection-state indicators and explicit reconnect action.
- Added regression tests for terminal IME input and SSH negotiation scenarios.

### Changed
- Standardized background SSH/session work on structured coroutines.
- Unified user-facing error messaging for connection, auth, and storage failures.
- Improved release workflow with documented checklist and stronger CI validation.

### Fixed
- Fixed edge-case session lifecycle regressions around keyboard and reconnect handling.
```

## Definition of done for 0.5.0

- All P0 scope items implemented and manually verified on at least one physical device.
- Unit and instrumentation tests pass for release branch.
- `./gradlew lint testDebugUnitTest assembleRelease bundleRelease` passes locally or in CI.
- `CHANGELOG.md`, version fields, and release notes are aligned.
- Release artifact is installable and terminal + SSH happy path is smoke-tested.
