# Roadmap

## Mission

Make managing remote Linux systems from Android as natural as a conversation.
Sushi is an SSH client where the primary interface is talking *to* the connected system — the AI persona lives on the target, the phone is just the window.

---

## Current state — v0.5.0 + unreleased polish

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

| PR | Branch | Scope | Status |
|----|--------|-------|--------|
| — | ~~`feature/play-parameter-ux`~~ | Play parameter defaults, descriptions, examples; live preview in run dialog; required-vs-optional distinction | ✅ |
| 6 | `feature/gemini-transcript` | Gemini transcript persistence — SQLite-backed session history with command/output entries, browsable history UI | 🔄 in progress |
| 4 | `feature/raw-terminal-mode` | [Raw Terminal Mode toggle](docs/features/raw-terminal-mode.md) — switch between AI conversation and direct shell | — |
| 5 | `feature/output-streaming` | Command output streaming — token-by-token delivery for long-running commands | — |
| 7 | `feature/first-run-onboarding` | [First-run onboarding](docs/features/first-run-onboarding.md) — guide user through `SUSHI.md` setup on first connect | — |

---

## v0.7.0 — Persona and file operations

Close the loop on persona editing and add the file operations that conversational users naturally ask for.

- **[Remote SUSHI.md editor](docs/features/persona-editor.md)** — read and write `~/.config/sushi/SUSHI.md` from within the app; right now customizing the persona requires a separate SSH terminal
- **[SFTP file operations](docs/features/file-operations.md)** — "download this log to my phone" is a natural conversational request; `SshClient` already handles upload via the Share target, download is the missing half *(B-17)*
- **Custom log location** — read `log_dir` from `~/.config/sushi/config.conf` and honour it; the config key exists, enforcement does not
- **Connection keep-alive in background** *(T-8)* — session should only disconnect on explicit action, not when the app backgrounds

---

## v0.8.0 — Intelligence and multi-system

Expand what the AI layer can do and scale to more than one host.

- **[AI-powered troubleshooting](docs/features/ai-troubleshooting.md)** — structured diagnosis: check service → read logs → suggest fix → confirm → verify; builds on the existing safety model
- **[Command history](docs/features/command-history.md)** — store past executed commands in SQLite; surface them in conversation ("run the same disk check as last time") and as a browsable list
- **[Multiple host persona awareness](docs/features/multi-system.md)** — each host has its own `SUSHI.md`; conversation context should make clear which system is active and allow switching without losing state

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
| SOCKS proxy (B-11) | P2 | |
| Sensor capture to remote file (B-12, B-13, B-14) | P1–P2 | |
| Pin default host (B-8) | P2 | |

---

## Far future / ideas

Interesting directions that depend on the conversational core being solid first. No commitment on timing.

- **[Voice I/O enhancements](docs/features/voice-io.md)** — wake word, text-to-speech responses, voice-only mode
- **[Home Assistant integration](docs/features/home-assistant.md)** — control smart home devices through the Sushi conversation layer
- **[Persona templates](docs/features/persona-templates.md)** — pre-made `SUSHI.md` starters for web server, dev environment, RetroPie, etc.
- **[Visual system dashboard](docs/features/system-dashboard.md)** — live CPU, memory, temperature, and service status graphs
- **[Proactive monitoring](docs/features/proactive-monitoring.md)** — target-side alerts surfaced on connect ("disk at 95%")
- **[Conversation branching](docs/features/conversation-branching.md)** — fork a conversation to simulate what-if scenarios without executing
- **[LLM-generated skills](docs/features/llm-generated-skills.md)** — ask the system to learn a new capability; generates and saves a script to the target

---

*Last updated: 2026-05-04*
