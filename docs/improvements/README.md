# Improvement proposals

A codebase review of Sushi (v0.7.1, 2026-07) produced these improvement proposals.
Each document describes concrete findings with file references, proposed changes,
and a rough effort estimate. They complement — and cross-reference — the existing
[ROADMAP.md](../../ROADMAP.md).

## Documents

| # | Document | Theme |
|---|----------|-------|
| 1 | [Security](01-security.md) | Host key verification, key handling, secrets |
| 2 | [Terminal emulation](02-terminal-emulation.md) | Real VT100 emulation, missing keys, rendering performance |
| 3 | [Connection reliability](03-connection-reliability.md) | Background keep-alive, reconnect, auth methods |
| 4 | [Architecture & code quality](04-architecture-code-quality.md) | God activities, threading, blocking APIs |
| 5 | [Testing & CI](05-testing-ci.md) | Unit-test coverage, static analysis |
| 6 | [UX](06-ux.md) | Extra-keys row, font size, search, sessions |

## Priority summary

**P0 — do first (security-critical or blocks core use cases)**

| Item | Doc | Why |
|------|-----|-----|
| Enable SSH host key verification (TOFU + known-hosts store) | [1](01-security.md) | `StrictHostKeyChecking=no` today → silent MITM exposure |
| Support passphrase-protected private keys | [1](01-security.md) | Encrypted keys (the recommended kind) cannot be used at all |
| Extra-keys row: Esc, arrows, Ctrl modifier | [2](02-terminal-emulation.md), [6](06-ux.md) | Without Esc/arrows, shell history and most CLI tools are unusable |

**P1 — high value**

| Item | Doc | Why |
|------|-----|-----|
| Real terminal emulation (screen buffer, cursor addressing) | [2](02-terminal-emulation.md) | vim/htop/less do not render today |
| Foreground service keep-alive + auto-reconnect | [3](03-connection-reliability.md) | Already on roadmap (T-8); sessions die on backgrounding |
| `keyboard-interactive` auth support | [3](03-connection-reliability.md) | Many servers (esp. with 2FA) require it |
| Per-host SSH identities | [1](01-security.md) | Single global key pair today (roadmap A-6) |
| Break up `MainActivity` (1230 lines) | [4](04-architecture-code-quality.md) | Biggest maintainability risk |
| Rendering performance: incremental append instead of full re-parse | [2](02-terminal-emulation.md) | O(n²) behaviour on long sessions |

**P2 — worthwhile**

- Migrate off deprecated `MasterKeys`/`security-crypto` APIs ([1](01-security.md))
- Converge threading on coroutines; make `SshClient` suspend-friendly ([4](04-architecture-code-quality.md))
- Typed exception classification instead of message-string matching ([4](04-architecture-code-quality.md))
- ktlint/detekt + coverage in CI; burn down `lint-baseline.xml` ([5](05-testing-ci.md))
- Font-size pinch, scrollback search, session export ([6](06-ux.md))
- Document `CommandSafety` as best-effort; close easy bypasses ([1](01-security.md))
