# GitHub Milestone Plan: v0.5.0

Use this document to track v0.5.0 execution in GitHub with one issue per P0/P1 item from the release and Gemini plans.

Source planning docs:
- `docs/RELEASE_0.5.0_PLAN.md`
- `docs/GEMINI_0.5.0_PLAN.md`

## Milestone definition

- **Title**: `v0.5.0`
- **Description**: Security/reliability hardening release with Gemini flow upgrades and stronger test coverage.
- **Target date**: set when scope is committed.

## Execution checklist

- [ ] Create milestone `v0.5.0`.
- [ ] Create all P0 issues and assign to milestone.
- [ ] Create all P1 issues and assign to milestone.
- [ ] Add labels (`area:*`, `priority:*`, `type:*`).
- [ ] Confirm dependencies are reflected in issue bodies.
- [ ] Start with P0 only; pull P1 only when P0 is stable.

## Issue backlog (P0/P1)

## P0

### 1) Gemini: allow Nano-only usage without cloud auth
- **Title**: `Gemini: allow Nano-only command generation without cloud auth`
- **Labels**: `area:gemini`, `priority:P0`, `type:enhancement`
- **Depends on**: none
- **Definition of done**:
  - Gemini entry path does not redirect to settings when Nano is available.
  - Voice/text command generation works with Nano and no API key.
  - Status text clearly indicates Nano routing.

### 2) Gemini: clarification loop (`CLARIFY:`)
- **Title**: `Gemini: implement CLARIFY follow-up flow in dialog`
- **Labels**: `area:gemini`, `priority:P0`, `type:feature`
- **Depends on**: #1 recommended but not required
- **Definition of done**:
  - `CLARIFY:` responses are parsed and shown as follow-up questions.
  - User can answer in dialog and regenerate to final command.
  - Transcript records clarify turns.

### 3) Gemini: command safety tier + risky confirmation
- **Title**: `Gemini: add command risk tiers and risky-run confirmation`
- **Labels**: `area:gemini`, `area:security`, `priority:P0`, `type:feature`
- **Depends on**: #2 optional
- **Definition of done**:
  - All generated commands are classified as `Safe` / `Review` / `Risky`.
  - `Risky` commands require explicit confirmation before run.
  - Classification is deterministic and tested.

### 4) SSH: host key TOFU and mismatch handling
- **Title**: `SSH: add host key TOFU trust store and mismatch decision flow`
- **Labels**: `area:ssh`, `area:security`, `priority:P0`, `type:feature`
- **Depends on**: none
- **Definition of done**:
  - First connect prompts for fingerprint trust.
  - Trusted keys persist locally.
  - Mismatch flow offers cancel / trust new / replace existing.

### 5) Terminal: connection state and reconnect affordance
- **Title**: `Terminal: add explicit connection state banner and reconnect action`
- **Labels**: `area:terminal`, `priority:P0`, `type:enhancement`
- **Depends on**: none
- **Definition of done**:
  - UI states shown: `Connecting`, `Connected`, `Reconnecting`, `Disconnected`.
  - Reconnect action appears on disconnection.
  - User-facing messages explain likely cause.

## P1

### 6) Gemini: text prompt mode and "reuse last prompt"
- **Title**: `Gemini: add text prompt input mode and reuse-last-prompt action`
- **Labels**: `area:gemini`, `priority:P1`, `type:feature`
- **Depends on**: none
- **Definition of done**:
  - User can submit prompts via text from the Gemini dialog.
  - Last prompt can be recalled with one tap.

### 7) Gemini: persistent transcript storage
- **Title**: `Gemini: persist transcript across app restarts`
- **Labels**: `area:gemini`, `priority:P1`, `type:feature`
- **Depends on**: #2
- **Definition of done**:
  - Transcript survives app/process restart.
  - Each entry stores timestamp, backend, prompt, response, and user action.
  - Clear-history action is available.

### 8) Gemini: insert-to-terminal and run-now actions
- **Title**: `Gemini: add insert-to-terminal and run-now actions`
- **Labels**: `area:gemini`, `area:terminal`, `priority:P1`, `type:feature`
- **Depends on**: #3
- **Definition of done**:
  - Actions available: `Copy`, `Insert`, `Run Now`.
  - `Run Now` follows safety-tier confirmation rules.

### 9) Refactor: coroutine and error-handling consistency
- **Title**: `Core: standardize background work on coroutines and unify error mapping`
- **Labels**: `area:core`, `priority:P1`, `type:refactor`
- **Depends on**: none
- **Definition of done**:
  - Reduced mixed raw thread + UI callback paths in touched components.
  - Consistent error-to-UI messages for SSH/network/storage boundaries.

### 10) Tests: regression coverage for Gemini and connection lifecycle
- **Title**: `Tests: add regression coverage for Gemini flows and reconnect lifecycle`
- **Labels**: `area:tests`, `priority:P1`, `type:test`
- **Depends on**: #1, #2, #3, #5
- **Definition of done**:
  - Coverage for clarify flow, safety classification, and Nano/cloud routing.
  - Coverage for disconnect/reconnect and lifecycle recovery paths.

## Suggested label set

- `area:gemini`
- `area:ssh`
- `area:terminal`
- `area:security`
- `area:tests`
- `area:core`
- `priority:P0`
- `priority:P1`
- `type:feature`
- `type:enhancement`
- `type:refactor`
- `type:test`

## Suggested milestone order

1. P0 #1, #4, #5 in parallel.
2. P0 #2 then #3.
3. P1 #6, #7, #8.
4. P1 #9 ongoing during implementation.
5. P1 #10 near feature-complete.

## PR checklist (attach to each milestone PR)

- [ ] Linked to milestone issue.
- [ ] User-visible strings moved to `strings.xml`.
- [ ] Unit/instrumentation tests added or updated.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] Relevant manual QA steps documented in PR body.
