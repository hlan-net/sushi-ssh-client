# UX proposals — from user story to merged screen

Every change that touches a layout or an activity passes through a Figma
proposal card, and the **UX Gate** CI job refuses a PR that does not link
one (or does not say, explicitly, that nothing visible changes). This
document is the one place that says how the whole path works — for the
person or agent *making* a proposal and, just as much, for the one
*implementing* it.

Sushi Figma file: `https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi`
— pages *Foundations* (palette), **Components** (five base components on
the *Sushi* variable collection — Light/Dark colour tokens whose Android
code syntax is the `R.color` name, plus *Sushi Layout* for spacing, radius
and the 48 dp touch target, and the `Sushi/*` text styles), *Main Screens*,
*Secondary Screens*, *Dialogs & Overlays*, *Navigation Flow*, and
**Proposals**, where every card lives. Sketch with the components and the
tokens where they fit: `get_design_context` then hands the implementer
token names instead of hex values.

## 1. The pipeline

```
GitHub issue, label `user-story`
  → ux-analysis.yml posts a "🎨 UX Analysis" comment      (scripts/prompts/ux-analysis.md)
  → proposal card on the Proposals page                    (scripts/create-ux-proposal.sh, or by hand)
  → design work in the card's Proposed Design panel; status In Design
  → status Ready for Review; maintainer looks; status Approved
  → implementing PR links the card under "Figma frame"     (.github/pull_request_template.md)
  → ux-gate.yml passes                                     (.github/workflows/ux-gate.yml)
  → merge; status Merged
```

A card can also start from the roadmap rather than an issue — the three
cards of 2026-09-21 (B-18, the conversation screen, the v0.9.x touches)
did — in which case the *Issue / PR* line points at the roadmap section and
the PR that introduced it. The rewrite plan's Phase 5 is nine such cards
plus a navigation map, all made in its Phase 0 and each *Approved* before
its screen is built; the plan's default there is a 1:1 port, so a card
that shows today's screen unchanged is a valid, finished card.

## 2. Anatomy of a card

The template is the frame **TEMPLATE — Duplicate to start a proposal** on
the Proposals page. A card is 820 × 580 and reads left to right:

| Region | What it is | What it means when you implement |
|---|---|---|
| Title + status badge | Feature name; *In Design* / *Ready for Review* / *Approved* / *Merged* / *On Hold* | Only *Approved* is safe to build from. *In Design* may still change. |
| Issue / PR · Author · Date | Where it came from | The issue or roadmap section is the requirement; the card is its shape. |
| **Problem** | What is wrong today, concretely | The acceptance test in prose: after the PR, this sentence must be false. |
| **Goal** | What the user can do afterwards | The second acceptance test: this must be true. |
| **UX Checklist** | Six boxes — visual style, 360 dp, empty/error states, strings, permissions, accessibility | Owned by the implementer (§5). |
| **Current State** panel | Screenshot or sketch of today | What must *not* survive the PR. If the current state is not drawn, draw it before designing — half the value of a card is the contrast. |
| **Proposed Design** panel | The sketch you build from | Structure and states, not pixels (§4). |
| **Notes / open questions** | Edge cases and decisions left open | Every open question must be closed — in the PR description or by editing the card — before the card goes *Approved*. |

The sketches are deliberately low fidelity: dark `#131C1A` panels, Inter at
7–10 px, JetBrains Mono for terminal text, the Foundations palette. They
show *which* elements exist, in *what* order, with *what* text — not the
final spacing.

## 3. Making a card

**From an issue, with the script.** `./scripts/create-ux-proposal.sh <issue>`
needs `gh`, the `claude` CLI, and the Sushi file open in the Figma desktop
app (the MCP server talks to the open file). It reads the UX-analysis
comment, builds the card, and posts the frame link back on the issue.

**By hand or by an agent.** Clone the template node (`node.clone()` keeps
every style exactly), move it 660 px below the lowest card, set the texts,
remove the two placeholder texts in the panels, then build the sketches
inside the panels as auto-layout frames. The page's own conventions — 220
px wide sketch roots, `#131C1A` panels, 7–10 px Inter — are what makes a
card readable next to the others. The text boxes for Problem and Goal are
fixed at 76 px: about 150 characters each. Notes: about 190. Longer text is
clipped, not wrapped.

**Link format.** `https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=<id with : as ->`
— a *frame* link, never the file root. Paste it into the issue, the roadmap
line, the feature document, and later the PR.

## 4. Implementing from a card

This is the part that was undocumented. An implementer — a person or an
agent — with a card and a task does the following, in order.

1. **Read the card, not a screenshot of it.** With the Figma MCP:
   `get_metadata(fileKey, nodeId)` for the structure, then
   `get_design_context(fileKey, nodeId)` on the *Proposed Design* panel for
   the element list, texts and colours. A screenshot is for orientation;
   the node tree is the specification.
2. **Confirm the status is *Approved*.** If it is *In Design* or *Ready for
   Review*, the design is not settled: ask the maintainer on the PR or
   issue before building. Do not build from a card and then discover it
   changed.
3. **Turn the sketch into a list.** Every text on the proposed panel is a
   string in `res/values/strings.xml` **and** in `values-de`, `-es`, `-fi`,
   `-sv`. Every interactive element is a view id in `lower_snake_case` (or
   a Compose test tag). Every distinct state the card shows (empty, error,
   loading, reconnecting…) is a state the code must reach. Write the list
   into the PR description; it is the checklist reviewers use.
4. **Choose the pattern by `CLAUDE.md`, not by the card.** The card says
   *what*; `CLAUDE.md` says *how*: a new screen is Compose + ViewModel; an
   element added to a legacy screen is the smallest change that puts it
   there. A card never asks for UI logic inside `MainActivity` or
   `SettingsActivity`, and a PR does not add any.
5. **Match structure and states, not pixels.** The card is right about which
   elements exist, their order, their text and which state shows what.
   Spacing, exact sizes and typography come from the app's existing
   Material 3 theme and the screens around the change. If matching the
   card would break the platform's own conventions (touch targets, IME
   behaviour, back navigation), the platform wins — and you note it.
6. **When you must deviate, say so twice.** Edit the card's *Notes* with
   the deviation and why, and put the same sentence in the PR description
   under *UI / UX changes*. A deviation nobody wrote down is the next
   person's confusion.
7. **Tick the UX Checklist on the card** — it is yours: visual style (the
   change looks like the screens beside it), 360 dp (test on a small
   emulator or the device runner), empty and error states (every state the
   card shows exists and is reachable), strings (all five locales),
   permissions (none added, or the manifest change is in the PR),
   accessibility (TalkBack reads the new element in order and says what it
   is; every touch target is at least 48 dp; nothing conveys meaning by
   colour alone).
8. **Link the card in the PR** under *Figma frame*, tick *Yes*, and let the
   UX Gate confirm. When the PR merges, set the card's status to *Merged*.

A card that does not exist is not a reason to design in code. If the task
has no card and touches a layout, make the card first (§3) or ask the
maintainer whether the change is small enough for *No — purely logic*.

## 5. Who moves what

| Transition / action | Owner | When |
|---|---|---|
| *In Design* → *Ready for Review* | proposer | both panels drawn, Notes' open questions listed |
| *Ready for Review* → *Approved* / *On Hold* | maintainer | after reading the card; open questions answered in Notes |
| Ticking the UX Checklist | implementer | as each box becomes true during the PR, not at the end |
| Editing Notes with a deviation | implementer | before the PR is marked ready for review |
| *Approved* → *Merged* | implementer | when the PR merges |
| Refreshing `scripts/prompts/ux-analysis.md`'s app context | whoever ships a new screen | in the PR that ships it — a stale context makes the bot propose against screens that no longer exist |

## 6. The gate, exactly

`ux-gate.yml` runs on every PR. It lists the changed files; if any is a
layout, menu, `strings.xml` or `colors.xml` in **any** `values*` directory
(a translation-only change is a visible change), `*Activity.kt`,
`*Fragment.kt`, `*Adapter.kt`, or a Compose screen (`ui/**/*.kt`,
`*Screen.kt`, `*Theme.kt`), it requires the PR body to
contain either a `figma.com/design/` (or `/proto/`, `/board/`) link or the
checked box `[x] No — purely logic/backend change`. Otherwise it posts a
comment with the two ways to fix it **and fails** (`exit 1`). Until
2026-09-21 it was a soft warning that did not block; it is a real check
now, which is what this document has always described.

*No — purely logic* is legitimate when an `Activity.kt` change has no
visible effect: a refactor, a bug fix in a listener, a renamed id. It is
not legitimate for a new element, a changed string, or a changed state —
those get a card even when they feel small. The v0.9.x touches are the
example: a field, a line, a bar and a banner, one card between them.

## 7. For agents

The path above is written so an agent can follow it without judgement
calls, except two:

- **A card in *In Design* or *Ready for Review*:** stop and ask; do not
  build from it.
- **A task that needs UI the card does not show:** stop and ask; do not
  invent it. The reply either adds it to the card or drops it from the
  task.

Everything else is mechanical: read the node tree, list the elements,
apply `CLAUDE.md`, build, tick the boxes, link, and move the status.

## 8. Related

- `.github/pull_request_template.md` — the *UI / UX changes* section the gate reads
- `.github/workflows/ux-gate.yml`, `.github/workflows/ux-analysis.yml`
- `scripts/create-ux-proposal.sh`, `scripts/prompts/ux-proposal-figma.md`, `scripts/prompts/ux-analysis.md`
- `docs/process/UX_FLOWS.md` — the navigation map the analysis bot reasons against
- `docs/features/*.md` — feature documents; each links its card once one exists
- `ROADMAP.md` — sections link their cards
