# Roadmap

## Mission

Make managing remote Linux systems from Android as natural as a conversation.
Sushi is an SSH client where the primary interface is talking *to* the connected system — the AI persona lives on the target, the phone is just the window.

---

## Current state — v0.8.3

The conversational foundation is complete:

- SSH client with terminal, Plays, jump-server support, key/password auth
- Target-side AI persona (`~/.config/sushi/SUSHI.md`) auto-generated on first use
- Three-tier command safety: SAFE (auto-execute) / CONFIRM (ask first) / BLOCKED (never)
- Dual text + voice input in Gemini dialog
- Chat-style transcript bubbles in the conversation UI
- Gemini Cloud (Flash/Pro) and on-device Nano support
- Conversation logs saved to `~/.sushi_logs/` on target
- Figma UX gate and AI-assisted proposal workflow for the dev process

---

## v0.6.0 — Terminal foundation + conversation depth

Solidify the terminal layer first — the conversational features all sit on top of it.

### Terminal foundation ✅ (all merged — 2026-05-04)

| PR | Branch | Scope | User-visible? |
|----|--------|-------|--------------|
| 1 | ~~`refactor/terminal-backend`~~ | Extract `TerminalBackend` interface from `SshClient`; `ConversationManager` + `PlayRunner` decoupled from `SshClient`; `LocalShellBackend` works with AI and Plays | No |
| 2 | ~~`feature/local-shell`~~ | `LocalShellBackend`, `HostKind` discriminator, synthetic "Local shell" host, `HostEditActivity` field toggle | Yes |
| 3 | ~~`feature/host-selection-ux`~~ | One-tap connect from host list; active host in terminal title bar; drop silent first-host auto-select; remove duplicate "Manage Hosts" in Settings | Yes |

### Conversation depth

| Feature | Branch | Scope | Status |
|---------|--------|-------|--------|
| Play UX | ~~`feature/play-parameter-ux`~~ | Play parameter defaults, descriptions, examples; live preview in run dialog; required-vs-optional distinction | ✅ |
| Transcript persistence | ~~`feature/gemini-transcript-persistence`~~ | Gemini transcript persistence — SQLite-backed session history with command/output entries per turn | ✅ |
| History UI | ~~`feature/gemini-history-ui`~~ | Gemini history browser — session list + turn detail view, delete session, command/output display | ✅ |
| Connection errors | ~~`feature/connection-error-classification`~~ | Connection error classification — typed `ConnectFailure` enum, actionable error banner, smart retry gating | ✅ |
| [Setup checklist](docs/features/first-run-onboarding.md) | ~~`feature/first-run-setup-checklist`~~ | First-run setup checklist — persistent card guides new users through SSH host + key + optional Gemini/Drive | ✅ |
| [Raw terminal mode](docs/features/raw-terminal-mode.md) | ~~`claude/roadmap-priorities-ds0cbs`~~ | Raw Terminal Mode toggle in the Gemini dialog — input goes straight to the shell, bypassing Gemini; still classified/logged via `CommandSafety` and the transcript | ✅ |
| Output streaming | ~~`claude/roadmap-priorities-ds0cbs`~~ | Command output streaming — `TerminalBackend.execCommand` takes an `onChunk` callback so long-running command output appears incrementally in the Gemini dialog instead of only after completion | ✅ |

---

## v0.7.0 — Persona, file operations, and terminal depth

Close the loop on persona editing and add the file operations that conversational users naturally ask for.

- **[Remote SUSHI.md editor](docs/features/persona-editor.md)** ✅ — read and write `~/.config/sushi/SUSHI.md` from within the app (Settings → Gemini → Edit persona), with save validation, an overwrite confirmation, and a "Reset to default" that re-runs the init script; no separate SSH terminal needed
- **[SFTP file operations](docs/features/file-operations.md)** ✅ — `SshClient.sftpDownload` plus a "Download file" action on the terminal tab pull a remote file to the phone and offer Open/Share; completes the download half that upload-via-Share was missing *(B-17)*. Conversational file intents remain a future enhancement.
- **Custom log location** ✅ — reads `log_dir` from `~/.config/sushi/config.conf` and honours it (falling back to `~/.sushi_logs`), so conversation logs can be directed to a mount point, RAM disk, or network share
- **Connection keep-alive in background** *(T-8)* ✅ — session survives app backgrounding via foreground service (`SshConnectionService`) and JSch `serverAliveInterval` keep-alive probes

---

## v0.8.0 — Intelligence and multi-system

Expand what the AI layer can do and scale to more than one host.

- **[AI-powered troubleshooting](docs/features/ai-troubleshooting.md)** ✅ — the conversation chains diagnosis automatically (command → output → interpretation → next command) until the AI concludes. Every step still goes through `CommandSafety`: CONFIRM pauses for the user and resumes on approval, BLOCKED ends the run. Capped at 5 commands per message, stops on a repeated command, and switchable off in the Gemini dialog
- **[Command history](docs/features/command-history.md)** ✅ — `CommandHistoryDatabaseHelper` stores every executed command (AI, raw mode, Play) with host, condensed output, exit status and source; browsable and searchable from the Terminal tab with copy / delete / re-run, capped at 500 entries per host. Recent entries for the connected host are injected into the AI prompt so it can compare against previous results. Two things are deliberately *not* recorded: Terminal-tab PTY keystrokes, since line-buffering raw input would capture typed passwords, and any Play that substituted a `secret` parameter value into its command, since the rendered command holds that value in plaintext
- **[Multiple host persona awareness](docs/features/multi-system.md)** ✅ — Option B: an Infrastructure section built from the saved hosts (addresses, jump-host relations, which one is connected) is injected alongside each host's own `SUSHI.md`, so cross-system questions can be answered without connecting. The Gemini dialog names the active host, and switching hosts mid-conversation rebuilds the persona context and marks the boundary in the transcript

---

## v0.9.0 — One terminal model, conversation as a screen

The first two steps of the [rewrite plan](docs/process/plans/rewrite-plan.md), chosen because the current structure blocks them outright: the two `TerminalView` instances share a parser that lives inside a `TextView`, and the AI conversation is an `AlertDialog` inside `MainActivity` that lifecycle events tear down. Everything else in the plan stays a reference, not a commitment.

- [ ] **`TerminalBuffer`** — extract the escape state machine, CR overwrite, backspace and line trimming out of `TerminalView` into a pure Kotlin class; `TerminalView` becomes a renderer over it. Both layouts keep using `TerminalView`. `TerminalViewEscapeTest` and `TerminalViewLogLineTest` move to JVM with their assertions unchanged. No UX change. *(plan §3.3 seam — the interface a real VT emulator later drops in behind)*
- [ ] **`ConversationViewModel`** — owns the transcript, streaming output, CONFIRM state and the raw / auto-troubleshoot toggles as `StateFlow`; `ConversationManager` moves behind it. Adds the Compose BOM, since the next step uses it. The dialog keeps working beside it.
- [ ] **`ConversationScreen`** — the first Compose screen, replacing the `AlertDialog`; `DialogGeminiControlsBinding` and the dialog code deleted in the same PR; `AiConversationTest` ported to a Compose UI test. *(plan §5.8 · [Figma proposal](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=103-2))*

One PR each, in this order. Each leaves the app releasable.

---

## v0.9.x — Fixes and small features

Taken as a set on 2026-09-21, after checking that none contradicts another or the v0.9.0 work. Each is its own PR. Dependencies and ordering are stated where they exist; everything else can go in any order. The four that touch a layout — startup command field, status line, search bar, reconnect banner — share one [Figma proposal card](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=105-2).

### Fixes

- [ ] **Reboot Host does not reboot.** `ManagedPlays.kt` ships a built-in Play named *Reboot Host* whose script is `logout` and whose description says "Reboot placeholder". Make it `sudo reboot` — `CommandSafety` classifies that CONFIRM, which is right — and make the terminal expect the disconnect that follows rather than showing it as an error. *Interacts with auto-reconnect below: a reboot is a retryable disconnect, so reconnect's backoff should bring the session back once the host is up.*
- [ ] **A corrupt hosts blob deletes every host, silently.** `SshSettings.getHosts()` returns `emptyList()` on any parse exception, and the next `saveHost` overwrites the blob. Log the failure, keep the last-known-good JSON under `ssh_hosts_json_backup` before every write, and show a one-time error with a Restore action. *Prerequisite for the rewrite plan's Moshi → kotlinx.serialization switch; the new key is added to the plan's §4.1 data contract.*
- [ ] **Command history reaches cloud Gemini unredacted.** `ConversationContextBuilder` injects recent commands and their condensed output into the prompt. A `cat .env` in history sends its contents to Google with the next question. Add a `SecretRedactor` (known token shapes — `AKIA…`, `ghp_…`, `sk-…`, `Bearer …`, `password=…`, PEM blocks) applied to history *and* to the live command output before it is sent to a cloud model; the on-device Nano path stays unredacted, since keeping data on the device is what it is for. Unit-tested against a fixture of real-looking secrets. *Does not change what is stored; storage is already excluded from backup.*
- [ ] **No automatic reconnect.** Losing the connection shows a Reconnect button and nothing else, and on mobile networks connections are lost constantly. Reconnect automatically with exponential backoff (1 s, 2 s, 4 s … capped at 30 s, give up after 5 minutes) **only when `ConnectFailure.isRetryable`** — `HOST_KEY_UNTRUSTED` is deliberately not retryable and must not be, because auto-reconnecting would re-show the trust dialog the user just cancelled (`SshClient.kt:83–86`). After a successful reconnect, run the host's startup command again (below). Cancel on manual disconnect.
- [ ] **Delete `ExampleUnitTest` and `ExampleInstrumentedTest`.** Android Studio placeholders.
- [ ] **The Flash model no longer exists.** `GeminiClient.MODEL_FLASH` is `gemini-1.5-flash`; ai.google.dev lists no `gemini-1.5-*` model any more, so choosing Flash in Settings fails. `MODEL_PRO` (`gemini-2.5-pro`, the default) still works but is a generation behind `gemini-3.1-pro`. Fix in two steps in one PR: move both constants to the 3.1 ids, then stop hard-coding ids — fetch `GET /v1beta/models` once (cached, refreshed weekly), store the user's choice as a capability (*fast* / *capable*) and resolve it to an id at call time. Unit test: a fake model list with the ids renamed still resolves. *(`docs/process/DEPENDENCY_LIFECYCLE.md` §1, principle 1)*
- [ ] **Dependency update, one PR.** `androidx.credentials` 1.3.0 → 1.6.0 (and find out why the auto-merge did not carry it), `googleid` 1.2.1, AGP 9.4.1, NDK r27 → the newest LTS line with CMake 3.22.1 → 4.1.x, and **remove `com.jcraft:jzlib`** — the JSch jar ships its own `juz` compression and the app never enables compression; set `compression.s2c`/`c2s` to `none` explicitly so the choice is visible. Verify: `./scripts/check-versions.sh` shows no `->` rows except the deliberately deferred ones (§2 of the lifecycle doc); `JschRuntimeTest` and `LocalSshIntegrationTest` green. *(`docs/process/DEPENDENCY_LIFECYCLE.md` §1)*

### Small features

- [ ] **Startup command per host.** A new optional `startupCommand` field on the host (JSON field `startupCommand`, default `null`, so existing blobs parse unchanged — added to the rewrite plan's §4.1). Sent with `sendCommand` once the shell is up, on **every** connection including auto-reconnect, which is what the main use case needs: `tmux attach || tmux new`. The editor says this next to the field, so a non-idempotent command is a conscious choice. *Pairs with B-18: an agent in tmux is one connect away.*
- [ ] **Pin default host (B-8) + Quick Settings tile + App Shortcuts.** B-8 moves here from the backlog because the other two need it. The tile and the long-press shortcuts (three most recent hosts) both fire an intent with a host id that `MainActivity` connects to without showing the host list; in the rewrite's single-activity shape that intent maps to a route. *Order: B-8 first, then the two entry points in one PR.*
- [ ] **Terminal search and share.** Over `TerminalBuffer`, search is a scan of the line buffer and "share the last N lines" is `ACTION_SEND` with plain text. *After v0.9.0's `TerminalBuffer` PR; not before, because doing it over the current `TextView` would be O(n²) and then thrown away.*
- [ ] **One-line status on connect.** The *Initialize AI Persona* Play also writes `~/.config/sushi/status.sh` (disk, load, pending updates, last login — one line). On connect Sushi runs it over the exec channel, before the startup command, and shows the line in the terminal's status row. Absent script = feature off, so hosts initialised before this change behave as today. No AI, no cloud. *This is an additive change to the target-side files; the rewrite plan's rule 6 is amended to allow additive changes while freezing the meaning of existing ones.*

### Process

- [ ] **`dependabot-auto-merge.yml` must not merge a branch carrying non-Dependabot commits.** `dependabot/gradle/…play-services-auth-22.0.0` had a hand-made roadmap commit on it, which means the two processes have already crossed once. Guard: skip any PR whose commits have an author other than `dependabot[bot]`.
- [ ] **Refresh `docs/improvements/`** — its P0/P1 summary (2026-07) still lists host-key verification, passphrase keys, keep-alive and `keyboard-interactive` as open; all four have shipped. Marked in the README in this change; the per-document findings should be marked the same way so an agent does not re-fix them.

---

## Backlog — SSH client completeness

Solid SSH client features that are not core to the conversational goal but round out the product.

| Story | Priority | Notes |
|-------|----------|-------|
| Port forwarding — local to remote (B-7) | P1 | |
| Port forwarding — local through remote to third host (B-10) | P1 | |
| Reusable SSH identity across hosts (A-6) | P1 | |
| ~~Share sheet → SFTP upload (B-15)~~ | — | ✅ Shipped in v0.4.0 |
| SCP phone → host (B-16) | P1 | |
| Phrase quick-access slots in terminal (B-3) | P2 | |
| Phrase search / filter (B-1) | P2 | |
| Play finish notification (B-4) | P2 | |
| On-device log browser (B-5) | P2 | |
| [SOCKS proxy (B-11)](docs/features/tunneled-web-browsing.md) | P2 | Non-VPN foundation for tunneled web browsing |
| Sensor capture to remote file (B-12, B-13, B-14) | P1–P2 | |
| ~~Pin default host (B-8)~~ | — | Moved to v0.9.x, as the prerequisite of the Quick Settings tile and App Shortcuts |
| [Remote agent launcher (B-18)](docs/features/remote-agent-launcher.md) | P1 | Start `claude rc` (or another agent CLI) on the target in a discovered project directory, in `tmux` where available, with the pairing link surfaced as an *Open in Claude* action. Two general Play improvements underneath — interactive Plays and target-discovered parameter choices. Independent of the rewrite plan. [Figma proposal](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=102-2). |

---

## QA improvements

- **Containerized device-runner hardening** — keep `Device Tests` self-hosted Docker runner setup reproducible (`adb` available in container, explicit host ADB bridge env, clearer preflight failures when no device is visible).
- **UI test reliability on Android 15+** — reduce `NoActivityResumedException` flakes by standardizing wake/unlock/stay-awake prep and documenting that secure lockscreen must be disabled for Espresso device runs.
- **Dependabot auto-merge guard** — see v0.9.x → Process; the auto-merge workflow must refuse branches with non-Dependabot commits.
- **Minified androidTest dependency alignment** — keep `minifiedDebugAndroidTestRuntimeClasspath` versions aligned with app classpath (notably Guava Android flavor) to avoid AGP consistent-resolution breakage.

## Technical Debt

- **`lint-baseline.xml` suppresses 182 findings** (plus 48 live warnings) that no list tracked until 2026-09-21. It is a debt ledger by another name. Pay it down per module: each module the rewrite plan creates starts with no baseline, and `:app`'s baseline shrinks as screens leave it; delete the file at the plan's Phase 6. Until then, a PR may not add to it.

Paid off:

- ~~**View IDs contradicted the documented convention**~~ ✅ Resolved (unreleased) — all 293 declared IDs are now `lower_snake_case`, so the rule `CLAUDE.md` has always stated finally describes the code. 357 XML references and 84 `R.id.*` references moved across 46 files; the 21 files calling `binding.*` needed no edit at all, because view binding derives the same camelCase property from a snake_case ID.
- ~~**Migrate from GoogleSignIn to Credential Manager & Identity Authorization**~~ ✅ Shipped in v0.7.11 (#167) — `play-services-auth` 22.0.0 plus `androidx.credentials` and `com.google.android.libraries.identity.googleid`; `DriveAuthManager` uses `CredentialManager` and Identity `AuthorizationClient`. `GoogleSignIn` no longer appears anywhere in the source.

---

## Far future / ideas

Interesting directions that depend on the conversational core being solid first. No commitment on timing.

- **[Voice I/O enhancements](docs/features/voice-io.md)** — wake word, text-to-speech responses, voice-only mode; includes the Android Auto feasibility assessment (native Auto app not possible — voice-only mode is the in-car path)
- **[Desktop mode / external display support](docs/features/desktop-mode.md)** — hardware keyboard in the terminal, window-resize resilience, adaptive wide layouts for Android 16+ desktop windowing
- **[Home Assistant integration](docs/features/home-assistant.md)** — control smart home devices through the Sushi conversation layer
- **[Persona templates](docs/features/persona-templates.md)** — pre-made `SUSHI.md` starters for web server, dev environment, RetroPie, etc.
- **[Visual system dashboard](docs/features/system-dashboard.md)** — live CPU, memory, temperature, and service status graphs
- **[Proactive monitoring](docs/features/proactive-monitoring.md)** — target-side alerts surfaced on connect ("disk at 95%")
- **[Conversation branching](docs/features/conversation-branching.md)** — fork a conversation to simulate what-if scenarios without executing
- **[LLM-generated skills](docs/features/llm-generated-skills.md)** — ask the system to learn a new capability; generates and saves a script to the target
- **[Tunneled web browsing](docs/features/tunneled-web-browsing.md)** — reach web pages (including a server's internal-only pages) through the SSH connection via dynamic (SOCKS) forwarding; the full "open my pre-selected external browser through the tunnel, revert when done" experience needs an Android `VpnService` (tun2socks), deferred over the extra Play Store review that VPN apps carrying other apps' traffic require. Non-VPN alternatives — SOCKS-only exposure (B-11) or an in-app WebView — remain available for a lighter version

---

*Last updated: 2026-09-21*
