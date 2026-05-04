# First-Run Onboarding

## Problem

The conversational AI feature is invisible until the user knows to run "Initialize AI Persona" from the Plays tab. A first-time user who connects to a host and opens the Gemini dialog sees generic behaviour with no persona context. The setup step is not discoverable.

## Concept

Detect on first connect whether `~/.config/sushi/SUSHI.md` exists on the target. If it does not, surface a guided prompt in the Gemini dialog that explains what persona initialization does and offers to run it immediately.

## Flow

1. User connects to SSH host via Terminal
2. `PersonaClient.initialize()` reads `SUSHI.md` — returns not-found
3. Gemini dialog (or a banner in the Terminal tab) shows:
   > "I don't know this system yet. Run **Initialize AI Persona** to set up conversational access."
   > [Initialize] [Skip]
4. Tapping Initialize runs the managed Play inline — no tab switch required
5. On completion, persona loads and conversation becomes available

## Notes

- "Skip" should not prompt again for the current session, but should offer again on the next connect if SUSHI.md still does not exist
- The initialization Play output can be shown inline in the dialog so the user sees what is happening
- If the user is connecting to a system where they lack write access to `~/.config/`, the flow should explain the limitation rather than silently failing
