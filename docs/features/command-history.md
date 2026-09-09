# Command History

## Concept

A persistent, searchable log of every command executed through Sushi — via conversation, Plays, or raw terminal — stored locally in SQLite. History is per-host and survives app restarts.

## Features

- Store command, output summary, timestamp, host, and source (conversation / Play / raw)
- Search and filter by text, host, or date
- Tap to re-run or copy a past command
- Conversational access: "run the same disk check as last time" or "what did I run yesterday?"

## Integration with conversation

The AI can reference history in its context:

```
Recent commands on raspberrypi:
- [2026-04-28 14:32] df -h → 45GB free
- [2026-04-28 14:30] uptime → load 0.12
```

This lets the AI answer "has disk usage changed?" without re-running the command, or notice patterns ("disk usage has been growing each time you check").

## Implementation

- New `CommandHistoryDatabaseHelper` (same pattern as `PhraseDatabaseHelper`)
- Record on every successful `EXECUTE:` in `ConversationManager` and on every command sent via `TerminalActivity`
- New `HistoryActivity` or bottom sheet accessible from the Terminal tab
- Inject recent history (last N entries for current host) into the Gemini system prompt alongside `SUSHI.md`

## Scope limits

- Store command + condensed output (first N lines), not full output — avoids unbounded DB growth
- Cap history per host (e.g., 500 entries); prune oldest on insert
- Do not store BLOCKED commands

---

## Implementation status — shipped in v0.8.0

- `CommandHistoryRecord` / `CommandSource` — one row per executed command (host id + label, command, condensed output, exit status, source, timestamp).
- `CommandHistoryDatabaseHelper` — SQLite store following the `PhraseDatabaseHelper` pattern: `record()` inserts and prunes the host's oldest rows beyond 500, `search()` does case-insensitive text matching over command and output with an optional host filter, `getRecentForHost()` feeds the AI context, and `entriesFlow` exposes the list reactively.
- Recording points: `ConversationManager` records every command that reaches the shell (source `CONVERSATION` for AI-issued, `RAW` for Raw Terminal Mode), and `MainActivity.recordPlayInCommandHistory` records each Play's rendered command (source `PLAY`). Commands classified BLOCKED never execute, so they are never recorded.
- `CommandHistoryActivity` — reachable from the Terminal tab. Search field, host filter, clear-all, and a per-entry action sheet with Re-run / Copy / Delete. Re-run returns the command to `MainActivity` (`EXTRA_RERUN_COMMAND`) instead of opening its own connection, so it goes through the normal raw-mode path and is classified by `CommandSafety` like any other command; with no live conversation it is copied to the clipboard instead.
- AI context: `ConversationContextBuilder.recentCommandsSection` renders the last 10 entries for the connected host into the prompt below `SUSHI.md`.

### Deliberate scope limits

- **Terminal-tab keystrokes are not recorded.** `TerminalActivity` writes to an interactive PTY character by character; reconstructing commands would mean line-buffering every keystroke, which would also capture passwords typed at `sudo`/`ssh` prompts into a plaintext local database. Commands run through the conversation (AI or raw mode) and Plays are recorded instead.
- Output is stored condensed (first 5 non-blank lines, 500 characters) rather than in full.
