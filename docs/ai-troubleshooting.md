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
