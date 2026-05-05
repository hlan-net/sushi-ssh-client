# LLM-Generated Skills

## Concept

The user asks the system to learn a new capability. The AI generates a script, saves it to `~/.config/sushi/scripts/` on the target, and adds a reference to `SUSHI.md` so it can be invoked in future conversations.

## Example

```
User: "Learn how to monitor my Python app."

AI: Generates monitor_python_app.sh
    Saves to ~/.config/sushi/scripts/monitor_python_app.sh
    Adds to SUSHI.md:
      ## Custom Scripts
      - monitor_python_app.sh: check gunicorn process and recent log errors
```

Future session:
```
User: "Check my Python app."
AI: EXECUTE: ~/.config/sushi/scripts/monitor_python_app.sh
```

## Security concerns

Auto-generated code running on a server is a significant attack surface. Mitigations required before shipping:

1. **User review before save** — the generated script must be shown to the user for approval; never silently write to disk
2. **Explicit confirmation** — saving a skill is always a CONFIRM-level action regardless of content
3. **Script isolation** — scripts run as the SSH user, not root; `SUSHI.md` safety rules still apply
4. **No `sudo` in generated scripts** — blocked by `CommandSafety` classifier
5. **Sandboxing** — future: run generated scripts in a restricted shell or container

## Status

Concept only. The security review and user-approval flow need to be designed carefully before implementation. Do not ship without both.
