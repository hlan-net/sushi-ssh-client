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
