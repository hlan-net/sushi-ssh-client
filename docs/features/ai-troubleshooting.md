# AI-Powered Troubleshooting

## Concept

Rather than one-shot "ask a question, get a command", structured troubleshooting flows where the AI works through a problem end-to-end: check status → read logs → identify root cause → propose fix → confirm with user → execute → verify.

This is the natural extension of the existing `EXECUTE:` directive model into multi-step, stateful diagnosis.

## Example flow

**User**: "My web server isn't responding."

1. AI: "Let me check nginx status." → `EXECUTE: systemctl status nginx` (SAFE)
2. Output: `Active: failed` — AI: "Nginx has stopped. Checking logs." → `EXECUTE: journalctl -u nginx --since '10 minutes ago'` (SAFE)
3. Output: `bind() to 0.0.0.0:80 failed (98: Address already in use)` — AI: "Port 80 is in use. Checking what's holding it." → `EXECUTE: ss -tlnp | grep :80` (SAFE)
4. AI: "Process `apache2` is on port 80. I can stop apache2 and restart nginx. This requires confirmation."
5. User confirms → `EXECUTE: sudo systemctl stop apache2` (CONFIRM) → `EXECUTE: sudo systemctl start nginx` (CONFIRM)
6. AI: "Verifying..." → `EXECUTE: curl -s -o /dev/null -w "%{http_code}" http://localhost` (SAFE)
7. AI: "Nginx is running and responding with HTTP 200."

## Implementation

- The `ConversationManager` already supports multi-turn history and sequential `EXECUTE:` directives
- Troubleshooting flows are driven by the LLM reasoning over `SUSHI.md` context — no hardcoded decision trees needed at the app level
- `SUSHI.md` on the target can include a `## Known Issues` or `## Troubleshooting` section that biases the AI toward system-specific diagnosis steps
- The CONFIRM safety tier already provides the user checkpoint before destructive steps

## What makes this different from current behaviour

Currently, each user message produces one AI response with at most one `EXECUTE:` directive. Multi-step troubleshooting requires the app to re-enter the conversation loop automatically after each command result, feeding the output back to Gemini until the AI signals completion (e.g., "Issue resolved" with no further `EXECUTE:` directive).

The main addition is a "keep going" mode that chains command → output → LLM → command automatically, pausing only at CONFIRM-level steps.

---

## Implementation status — shipped in v0.8.0

The "keep going" mode described above lives in `ConversationManager.executeCommandAndRespond`, which is now a loop rather than a single exchange:

1. Run the command and capture real stdout/stderr via `TerminalBackend.execCommand`.
2. Record it in the command history and send the output back to the model for interpretation.
3. Parse the interpretation with `ExecuteDirective`. If it asks for another command, classify it and continue.

Chaining stops when any of these is true:

- the model returns no further `EXECUTE:` directive (the run reached a conclusion),
- `ConversationManager.MAX_TROUBLESHOOTING_STEPS` (5 commands per user message) is reached,
- the next command is identical to the one just executed (no progress),
- the next command is BLOCKED — the run ends with the safety explanation,
- the next command is CONFIRM — the run pauses and returns to the user; approving it calls `executeConfirmedCommand`, which resumes the chain where it left off,
- the user turned the "Multi-step troubleshooting" switch in the Gemini dialog off (`GeminiSettings.getAutoTroubleshootEnabled`, default on).

Each chained step is announced in the transcript through the existing streaming callback, so the user watches the diagnosis progress rather than waiting for one long answer.

What gets recorded, precisely: every command that reaches the shell gets its own row in the local command history, including one that timed out (it ran and may have had side effects). The conversation transcript and the target-side log store one turn per run, whose narrative includes each chained command line — so the run reads back in full, even though the turn's `commandExecuted` column names the last command.
