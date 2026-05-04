# Raw Terminal Mode

## Problem

The conversational AI layer is the primary interface in Sushi, but it is not always what the user needs. When the AI misunderstands a request, a long-running command is stuck, or the user simply wants direct shell access, there is currently no escape hatch. The absence of one erodes trust in the AI layer.

## Concept

A toggle that switches the Gemini dialog — or the main session view — between two modes:

- **Conversation Mode** (default): input goes to Gemini, which interprets and executes via `EXECUTE:` directives
- **Raw Mode**: input goes directly to the SSH shell, bypassing Gemini entirely

The mode switch should be visible and instant. Users should be able to drop into raw mode, run a few commands, and return to conversation without losing history.

## Use cases

- Advanced users who want direct shell access for complex scripting
- Debugging when AI responses are unhelpful or wrong
- Learning: see the actual commands the AI would run, then try variations manually
- Trust building: validate what the AI is doing by doing it yourself

## Implementation ideas

### Option A — toggle button in Gemini dialog
A small icon or label in the dialog header switches mode. The input field and send behaviour change; transcript continues accumulating in both modes so the full session history is visible.

### Option B — separate Raw tab in MainActivity
The existing Terminal tab gets renamed/split. `Tab 0: Conversation` and `Tab 1: Terminal` sit side by side. The terminal tab is always the raw shell; conversation tab is AI-mediated. Both share the same SSH connection via `SshConnectionHolder`.

Option B is architecturally cleaner (the existing `TerminalActivity` already handles raw input) but requires navigation changes. Option A is lower effort and keeps context in one place.

## Notes

- Deferred from v0.5.0 by design — shipped the AI layer first, raw mode is the follow-up
- The `CommandSafety` classifier should still log commands executed in raw mode for the conversation transcript
- Raw mode should not clear conversation history; the user returns to where they left off
