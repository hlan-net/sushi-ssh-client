# Remote Persona Editor

## Problem

`~/.config/sushi/SUSHI.md` on the target system is the core of the conversational experience — it defines personality, available commands, system purpose, and health parameters. Editing it currently requires opening a separate SSH terminal session and using a text editor. This breaks the in-app flow and is inaccessible to users who are not comfortable with terminal editors.

## Concept

A dedicated screen (or sheet) in the app that reads `SUSHI.md` via SSH, presents it as editable text, and writes it back on save.

## Features

- Load current `SUSHI.md` content on open (via `SshClient` cat or SFTP read)
- Editable `EditText` with monospace font and markdown-aware syntax highlighting if feasible
- Save button writes back to target via SSH (`echo`/heredoc or SFTP write)
- Confirm dialog before overwrite ("This will replace the current persona on `hostname`. Continue?")
- Validation before save: warn if file is empty or missing required sections (`## System Identity`, `## Personality`)
- "Reset to default" option re-runs the initialization script to regenerate a fresh persona

## Entry point

Settings → Gemini tab → "Edit Persona" button (only enabled when a host is connected and persona has been initialized).

## Implementation notes

- `SshClient` already supports command execution; read via `cat ~/.config/sushi/SUSHI.md`, write via a heredoc or temp-file-and-move pattern
- SFTP would be cleaner for write (avoids shell escaping issues) — tracked separately in the file operations concept
- Persona refresh after save: call `PersonaClient.initialize()` again so the updated context is used in the next conversation turn
- Keep the activity simple: read → display → edit → save. No syntax highlighting in the first iteration.
