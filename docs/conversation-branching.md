# Conversation Branching

## Concept

Fork the current conversation to explore an alternative path — ask "what would happen if I restarted the service?" — without actually executing anything. Merge back or discard the branch when done.

## Example

```
Main: "Check nginx status" → running, load 0.4

  Branch: "What if I restarted it?"
  AI simulates: service would go down briefly, connections reset, comes back up
  User: discard branch

Back to main conversation, nginx still running
```

## Use cases

- What-if scenarios before making changes to a production system
- Learning: explore consequences without risk
- Planning multi-step operations — walk through the steps in simulation before executing

## Implementation notes

- The AI simulates without executing: the branch prompt instructs Gemini to reason about what *would* happen if the command ran, using `SUSHI.md` context and known system state
- No `EXECUTE:` directives fire in a branch; the response is purely conversational
- Branch state is in-memory only; conversation history forks at the branch point and the fork is discarded on merge/discard
- UI: a "Branch" button in the conversation toolbar opens a branched session in a separate sheet or overlay with a clear visual indicator ("Simulation — not executing")

## Dependency

Depends on a solid single-session conversation history being in place first. This is a post-v0.8.0 concept.
