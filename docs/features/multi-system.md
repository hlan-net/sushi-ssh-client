# Multi-System Support

## Concept

Sushi already supports multiple saved hosts. The gap is that each host's persona is completely independent — there is no shared knowledge across systems and the AI has no awareness of your infrastructure as a whole.

## Options

### Option A — Independent personas (current)
Each host has its own `SUSHI.md`. No cross-host context. Simple; already works.

### Option B — Shared knowledge base
A common knowledge file (e.g., `~/.config/sushi/shared-knowledge.md`) lives on a designated "primary" host or in the app itself. It describes the overall infrastructure: which hosts exist, their roles, how they relate.

Each host's `SUSHI.md` includes a reference section:
```
## Infrastructure
- raspberrypi-home: home media server (this system)
- vps-prod: production web server
- nas: NAS at 192.168.1.10
```

The AI can answer cross-system questions ("which host runs my web server?") without connecting to the other host.

### Option C — Unified AI across all systems
One AI persona that is aware of all your systems and can orchestrate across them — connecting to host A to check something, then host B to apply a change.

This is the most powerful but requires a significant architecture change: multi-session management, cross-host command routing, and more complex safety rules.

## Recommended path

Start with Option B — enrich `SUSHI.md` with an infrastructure section during initialization (by probing the local network or letting the user describe it). Option C can evolve from there once multi-session SSH is supported.

## Related

- The "active host" indicator in the conversation UI should be prominent so the user always knows which system they are talking to
- Host switching mid-conversation should reset persona context and clearly mark the switch in the transcript

---

## Implementation status — Option B shipped in v0.8.0

- `ConversationContextBuilder.infrastructureSection` builds the `## Infrastructure` block from the app's saved hosts: name, target address (or "Android device shell" for the local host), jump-host relations, and a marker on the connected system. It is injected into the prompt next to that host's own `SUSHI.md`, so cross-system questions can be answered without connecting — and the model is told it can only run commands on the marked system. With fewer than two saved hosts the section is omitted entirely.
- The knowledge base lives in the app (the saved host list) rather than in a `shared-knowledge.md` on a designated primary host, so it needs no target-side setup and stays correct when hosts are added or removed.
- The Gemini dialog names the host the conversation is bound to ("Talking to <host>"), so the active system is never ambiguous.
- Switching hosts mid-conversation builds a fresh `ConversationManager` — persona, history, and infrastructure context are all rebuilt — and appends a "Host switched" marker to the transcript so earlier bubbles are not read as the new system's answers.

Option C (one AI orchestrating across several live sessions) still needs multi-session SSH and cross-host safety rules, and remains future work.
