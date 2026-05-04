# Home Assistant Integration

## Concept

If the connected host runs Home Assistant, wire the conversational layer to HA's REST API so the user can control smart home devices through the same Sushi conversation interface.

## Examples

- "Turn off the living room lights."
- "What's the temperature in the bedroom?"
- "Lock the front door."
- "Is the washing machine done?"

## Architecture

The integration lives in `SUSHI.md` on the HA host — consistent with the persona-on-target model. The initialization script detects HA (checks `ha` CLI, HA config directory, or `hass` process) and adds a `## Home Assistant` section to `SUSHI.md` with:

- HA API base URL (`http://localhost:8123`)
- Available entity categories (populated by querying `/api/states`)
- Sample commands using `curl` against the HA REST API

The AI then generates `curl` commands as `EXECUTE:` directives, which are classified as CONFIRM-level (since they affect physical devices).

## Authentication

HA long-lived access tokens stored in `SecurePrefs` (same pattern as other secrets in the app) and injected into the SUSHI.md context or passed as a parameter to the initialization script.

## Notes

- This does not require a custom HA integration or add-on — it uses only the standard REST API
- The SUSHI.md approach means the AI adapts to the user's actual entities rather than a hardcoded list
- Physical-world commands (lights, locks, appliances) should always be CONFIRM level regardless of what HA reports — never auto-execute
