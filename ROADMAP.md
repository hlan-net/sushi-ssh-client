# Roadmap

## Mission

Make managing remote Linux systems from Android as natural as a conversation.
Sushi is an SSH client where the primary interface is talking *to* the connected system — the AI persona lives on the target, the phone is just the window.

---

## Current state — v0.7.12 + unreleased v0.8.0 work

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
- **[Command history](docs/features/command-history.md)** ✅ — `CommandHistoryDatabaseHelper` stores every executed command (AI, raw mode, Play) with host, condensed output, exit status and source; browsable and searchable from the Terminal tab with copy / delete / re-run, capped at 500 entries per host. Recent entries for the connected host are injected into the AI prompt so it can compare against previous results. Terminal-tab PTY keystrokes are deliberately *not* recorded — line-buffering raw input would capture typed passwords
- **[Multiple host persona awareness](docs/features/multi-system.md)** ✅ — Option B: an Infrastructure section built from the saved hosts (addresses, jump-host relations, which one is connected) is injected alongside each host's own `SUSHI.md`, so cross-system questions can be answered without connecting. The Gemini dialog names the active host, and switching hosts mid-conversation rebuilds the persona context and marks the boundary in the transcript

---

## Backlog — SSH client completeness

Solid SSH client features that are not core to the conversational goal but round out the product.

| Story | Priority | Notes |
|-------|----------|-------|
| Port forwarding — local to remote (B-7) | P1 | |
| Port forwarding — local through remote to third host (B-10) | P1 | |
| Reusable SSH identity across hosts (A-6) | P1 | |
| Share sheet → SFTP upload (B-15) | P1 | Done in v0.4.0 |
| SCP phone → host (B-16) | P1 | |
| Phrase quick-access slots in terminal (B-3) | P2 | |
| Phrase search / filter (B-1) | P2 | |
| Play finish notification (B-4) | P2 | |
| On-device log browser (B-5) | P2 | |
| [SOCKS proxy (B-11)](docs/features/tunneled-web-browsing.md) | P2 | Non-VPN foundation for tunneled web browsing |
| Sensor capture to remote file (B-12, B-13, B-14) | P1–P2 | |
| Pin default host (B-8) | P2 | |

---

## QA improvements

- **Containerized device-runner hardening** — keep `Device Tests` self-hosted Docker runner setup reproducible (`adb` available in container, explicit host ADB bridge env, clearer preflight failures when no device is visible).
- **UI test reliability on Android 15+** — reduce `NoActivityResumedException` flakes by standardizing wake/unlock/stay-awake prep and documenting that secure lockscreen must be disabled for Espresso device runs.
- **Minified androidTest dependency alignment** — keep `minifiedDebugAndroidTestRuntimeClasspath` versions aligned with app classpath (notably Guava Android flavor) to avoid AGP consistent-resolution breakage.

## Technical Debt

- **Migrate from GoogleSignIn to Credential Manager & Identity Authorization** — Upgraded `play-services-auth` to 22.0.0 and added `androidx.credentials` + `com.google.android.libraries.identity.googleid`; rewritten `DriveAuthManager` to use modern Android Identity AuthorizationClient and CredentialManager.

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

*Last updated: 2026-09-09*
