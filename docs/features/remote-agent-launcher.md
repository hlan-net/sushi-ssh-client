# Remote Agent Launcher

*Backlog B-18 · P1 · proposed 2026-09-21*

## Problem

An AI coding agent that runs on the server — Claude Code's remote control
(`claude rc`), and whatever the other CLIs grow into — is the natural
partner for an app whose premise is talking *to* the connected system. Today
starting one from Sushi is manual and fragile: the user types
`cd ~/some/project && claude rc` into the terminal, copies the pairing link
by hand, and if they append `&` to keep the shell free, the process reads
from the TTY in the background, takes `SIGTTIN`, and stops — which is
exactly how the `409` in the 2026-09-20 session came about. Nothing in the
app knows the agent exists, so nothing can attach to it, show that it is
running, or kill it.

## Concept

A launcher on the Terminal tab: pick the tool, pick the project directory
from a list Sushi discovers on the target (or type a path), press Start.
Sushi runs the agent in the right directory, in a real foreground PTY, in a
`tmux` session when the target has one so it survives disconnects, shows the
pairing link the tool prints, and offers to open it in the phone's Claude
app. Running agents appear as chips on the Terminal tab with *Attach* and
*Kill*.

The result is a bridge: Sushi starts the agent on the server; the phone
drives it through the vendor's own app; Sushi's terminal is where the user
goes to watch it work.

## Use cases

- Start a Claude Code session on a home server or Raspberry Pi in a
  particular repository, then steer it from the Claude app on the phone,
  without ever typing a path.
- Come back an hour later, open Sushi, tap the running session's chip, and
  see the agent's terminal.
- Kill a runaway agent from the phone.
- Start any long-running interactive CLI (not only agents) in a chosen
  directory, detached, from a Play.

## How it fits what exists

Most of this is already a **Play**. `ManagedPlays.kt` upserts five built-in
Plays at startup; `{{ PARAM }}` templates, required/optional parameters,
preview, and `secret` parameters all exist. Two general Play improvements
make the launcher a managed Play rather than a feature of its own:

### 1. Parameters whose choices come from the target

`PlayParameter` gains `choicesCommand: String?`. When set, the run dialog
executes it over the exec channel (`TerminalBackend.execCommand`, fast, does
not touch the interactive PTY) and offers its output lines as a picker,
with a free-text "other" field beneath. The last value used is remembered
per host and shown first.

For the launcher the command is the marker-file search of the chosen tool:

```sh
find "$HOME" -maxdepth 4 -name CLAUDE.md \
  -not -path '*/node_modules/*' -not -path '*/.git/*' \
  -printf '%h\n' 2>/dev/null | sort
```

`-maxdepth 4` keeps it under a second on a home directory with a few
hundred repositories; a target that is slower than that gets the free-text
field and a "still searching" state, not a hang.

### 2. Plays that run in the interactive session

`Play` gains `interactive: Boolean`. `PlayRunner` runs every Play today
through `execCommand` with a 30 s timeout on a separate exec channel —
right for `chpasswd`, impossible for a process that runs for hours and
needs a TTY. An interactive Play is rendered the same way and then sent to
the live session with `TerminalBackend.sendCommand`; its output is the
terminal's output. No timeout, no capture, and it is never recorded in
command history (the same rule a `secret` parameter already triggers,
because the pairing link it prints is a credential).

## The two ways to run it

Sushi checks `command -v tmux || command -v screen` on the target once per
connection and picks the path:

| | `tmux` (or `screen`) present | neither |
|---|---|---|
| Start | `tmux new-session -d -s sushi-agent-<slug> -c <dir> '<command>'`, then `tmux attach -t sushi-agent-<slug>` in Sushi's PTY | `cd <dir> && <command>` in Sushi's PTY |
| Survives SSH disconnect | yes | no — the launcher says so before starting |
| Survives the phone sleeping | yes | while `SshConnectionService` keeps the session alive |
| "Running" chips | from `tmux ls -F '#{session_name}'`, filtered by the `sushi-agent-` prefix, refreshed on connect | the one process, for the life of the session |
| Attach / Kill | `tmux attach` / `tmux kill-session` | detach = Ctrl-C |

`<slug>` is the directory's basename, lower-cased, non-alphanumerics
replaced by `-`, so the same directory started twice attaches to the
existing session instead of starting a second one.

The `tmux` path is what makes the feature worth having: start the agent,
close Sushi, drive it from the vendor app, and come back to the terminal
when you want to see what it did.

## Tools

A built-in table; the user can add rows in Settings → SSH.

| Tool | Command | Marker file | Phone-side control |
|---|---|---|---|
| Claude Code | `claude rc` | `CLAUDE.md` | yes — prints a pairing link for the Claude app |
| Codex CLI | `codex` | `AGENTS.md` | not known to have an equivalent |
| Gemini CLI | `gemini` | `GEMINI.md` | not known to have an equivalent |
| Custom | template | chosen by the user | — |

Only Claude Code is known to have a pairing flow with a phone app. For the
others the value is the generic one: the CLI starts in the right directory,
in `tmux`, and the user attaches from Sushi's terminal — still better than
typing it by hand. The marker file is part of the tool definition, so the
directory list is per tool. A tool whose binary is missing on the target
(`command -v <tool>` fails) is shown greyed with "not installed on
<alias>", not hidden.

## The detail that makes it three taps

`claude rc` prints the pairing link. Sushi watches the session output for
the first `https://claude.ai/…` URL after an interactive Play starts and
shows an **Open in Claude** action above the terminal (an `ACTION_VIEW`
intent; the Claude app claims the link, the browser is the fallback). The
whole path is: pick the host → pick the directory → tap Open.

Without this the user copies the link out of the terminal by hand, which is
the step the feature exists to remove.

## UI

On the Terminal tab, an **agent** button beside the existing **phrases**
button opens a bottom sheet:

1. Tool — segmented control, built-ins plus custom entries.
2. Directory — the discovered list (last used first) and an "other path"
   field.
3. "Keep running after disconnect" — shown checked and locked when `tmux`
   is present, shown unchecked and disabled with an explanation when it is
   not.
4. Start.

Running sessions are chips in the terminal's status row, one per
`sushi-agent-*` `tmux` session, each with Attach and Kill.

This touches `activity_terminal.xml`, so the PR needs a Figma frame for the
sheet and the chips. The proposal card, with a current-state sketch beside
the sheet and the chips, is on the Proposals page:
[Remote agent launcher (B-18)](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=102-2).

## Implementation

Three PRs, each releasable, in this order. The first two are general Play
features with their own value.

1. **Interactive Plays** — `Play.interactive`, `PlayRunner` routing through
   `sendCommand`, the history exclusion, the "sent to terminal" state in
   the run dialog. Tests: `PlayRunner` unit tests for routing and the
   history rule.
2. **Discovered choices** — `PlayParameter.choicesCommand`, the picker in
   the run dialog, per-host last-used memory. Tests: parameter
   encode/decode round-trip; picker UI test with a fake backend returning
   fixed lines.
3. **The launcher** — the tool table, the `tmux` detection and wrapper, the
   managed Play *Start remote agent* (`interactive = true`, directory
   parameter with the tool's `choicesCommand`), the pairing-link watcher
   and the Open action, the chips. Tests: slug derivation; wrapper command
   rendering for both paths; link detection against recorded `claude rc`
   output; chips against a fake `tmux ls`.

Nothing here depends on the rewrite plan. It fits the current structure and
it fits the future one; the launcher sheet becomes a Compose screen
whenever the Terminal tab does.

## Scope limits

- **No agent output parsing** beyond the pairing link. Sushi does not try
  to understand what the agent is doing.
- **No agent orchestration.** One tool, one directory, one session per
  directory. Fleet-style "run this on all hosts" is a different feature.
- **No credential handling for the tools.** `claude` must already be logged
  in on the target; if it is not, its own login prompt appears in the
  terminal, which is the right place for it.
- **Not a replacement for the vendor app.** The phone drives the agent
  through Claude's app; Sushi starts it, watches it, and stops it.

## Security

The command runs as the SSH user in that user's home directory: no new
privilege on the target. The new surface is the **pairing link** — whoever
has it drives the agent — which is why it is never written to command
history and why the Open action goes straight to an intent rather than
through the clipboard. Directory discovery reads only names under `$HOME`;
nothing is executed in the discovered directories until the user presses
Start.

## Notes

- The `tmux` session name prefix `sushi-agent-` is the contract for the
  chips; a session the user started by hand with that prefix is shown and
  controllable, deliberately.
- `screen` support is a fallback for targets without `tmux` (older
  distributions, some embedded images); the wrapper differs only in the
  four commands.
- If the target has neither and the user starts an agent anyway, the
  launcher should say plainly that closing Sushi will end it, before Start,
  not after.
