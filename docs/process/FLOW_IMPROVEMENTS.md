# User Flow Improvements

This document analyzes the current Sushi user flow and proposes four concrete
improvements. Each improvement names the friction it removes, the change it
proposes, the files involved, and a rough scope estimate.

The analysis assumes the navigation structure documented in
[`UX_FLOWS.md`](./UX_FLOWS.md): `MainActivity` (Terminal/Plays tabs),
`SettingsActivity` (General/SSH/Gemini/Drive tabs), `TerminalActivity`,
`HostsActivity`, and the standalone management activities (`PlaysActivity`,
`PhrasesActivity`, `KeysActivity`).

---

## 1. Quick host switcher in the terminal toolbar

### Friction today

To switch SSH targets the user must:

1. Leave the active terminal (back button).
2. Open Settings or the Hosts list.
3. Pick a host (which marks it active in `SshSettings`).
4. Return to the terminal and tap **Connect** again.

`TerminalActivity` resolves the active host through
`SshSettings.getConfigOrNull()` (`SshSettings.kt:81-95`) and offers no in-place
way to choose a different one. Reconnecting to a recently-used host therefore
costs 4+ taps and a screen change every time.

There is also no visible affordance for jump-server routing — the resolution
happens silently inside `SshClient` (`SshClient.kt:89-150`), so users cannot
tell whether the next session will go through a bastion or directly.

### Proposal

Add a host dropdown to `TerminalActivity`'s toolbar that:

- Lists the most-recently-used hosts (top 5) plus a "More…" entry that opens
  `HostsActivity`.
- Marks the currently active host with a check mark.
- Shows a small "via <jump-host>" subtitle when the selected host has a jump
  server configured, so the routing is visible before connecting.
- Selecting a host updates `SshSettings.activeHostId` and, if a session is
  already open, prompts "Disconnect current session?" before switching.

### Files involved

- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt` (lines 99-167) —
  add toolbar menu, wire item-selection to `SshSettings`.
- `app/src/main/java/net/hlan/sushi/SshSettings.kt` — add a `recentHostIds`
  list (cap at 5), bump it on every successful connect.
- `app/src/main/res/menu/menu_terminal.xml` (new or extended) — host
  selector menu item.
- `app/src/main/res/values/strings.xml` — labels (`switch_host`,
  `connected_via_jump`, etc.).

### Scope

Small/medium. No protocol or storage changes; just a new MRU list in
`SecurePrefs` plus a menu item.

---

## 2. First-run connection-setup checklist

### Friction today

On first launch `MainActivity` shows the Terminal and Plays tabs as if
everything were configured. The Terminal tab's **Connect** button only fails
*after* the user taps it: `TerminalActivity` reaches
`SshSettings.getConfigOrNull()`, gets `null`, and shows an ephemeral toast
("Add SSH details in Settings"). Toasts disappear in seconds, and there is no
durable cue that:

- No host has been added yet.
- No SSH key has been generated (or imported).
- Gemini and Drive are optional but un-configured.

The empty-state hand-off currently lives in `MainActivity.kt:110-125` /
`HostsActivity.kt:46-58` and only triggers if the user happens to find the
"Configure Host" button.

### Proposal

Replace the Terminal tab's empty state with a **Setup checklist card** that is
shown only when no host is configured:

| Step | Required | Action |
|------|----------|--------|
| 1. Add an SSH host | Required | Opens `HostEditActivity` |
| 2. Generate or import a key | Recommended | Opens `KeysActivity` |
| 3. Add a Gemini API key | Optional | Opens Settings → Gemini |
| 4. Connect Drive for log backup | Optional | Opens Settings → Drive |

Each row collapses to a check mark once satisfied. Once step 1 is complete the
card hides itself (or is reachable via a "Setup" item in the overflow menu).
This makes required vs. optional configuration visible without forcing a
modal wizard.

### Files involved

- `app/src/main/java/net/hlan/sushi/MainActivity.kt` (lines 110-125) —
  show/hide checklist based on `SshSettings`, `KeyStore`, `SecurePrefs`.
- `app/src/main/res/layout/fragment_terminal.xml` (or the relevant include)
  — add the checklist card layout.
- `app/src/main/java/net/hlan/sushi/SetupChecklist.kt` (new) — pure-Kotlin
  helper that inspects state and returns which steps are done.
- `strings.xml` for step labels.

### Scope

Medium. The logic is small but adds a new layout and crosses several
config systems (`SshSettings`, `SecurePrefs`, key store, Drive auth manager).

---

## 3. Inline parameter validation and hints for Plays

### Friction today

`PlayRunner.execute()` (`PlayRunner.kt:14-91`) renders templated commands
using `{{ PARAM }}` placeholders. The current run flow in
`MainActivity.kt:500-667` is:

1. Tap "Run Play".
2. Pick host (modal dialog).
3. Pick play (modal dialog).
4. If the play has placeholders, a third dialog shows raw `TextInputEditText`
   fields with no examples, no required/optional distinction, and no hint
   that the syntax `{{ NAME }}` even exists.
5. Errors from execution land in the session console, not the dialog —
   users have to dismiss everything and scroll the log to see what went
   wrong.

There is also no way to step back: cancelling a parameter dialog returns
the user to step 1, not step 3.

### Proposal

Two concrete changes to the parameter dialog and the Play editor:

1. **Parameter dialog**:
   - Show the rendered command preview at the top, updating live as the user
     types (so they see the substitution).
   - Mark required parameters with a red asterisk; allow defaults to be
     declared in the play definition (`Play.kt:6-50` already has a `params`
     list — extend each entry with `default: String?` and `required: Boolean`).
   - Validate non-empty required fields before enabling **Run**.
   - Replace the hard "Cancel" → all-the-way-out behavior with a "Back"
     button that returns to the play picker.

2. **Play editor** (`PlaysActivity` / play edit form): show a one-line hint
   under the command field: `Use {{ NAME }} for parameters. Example:
   restart {{ SERVICE }}`.

### Files involved

- `app/src/main/java/net/hlan/sushi/Play.kt` — extend `Param` (or whatever
  the placeholder model is) with `default` and `required`.
- `app/src/main/java/net/hlan/sushi/PlayRunner.kt` — accept the new fields
  when rendering; treat missing required values as a hard failure with a
  user-readable error.
- `app/src/main/java/net/hlan/sushi/PlayDatabaseHelper.kt` — schema bump
  (new columns) + migration.
- `app/src/main/java/net/hlan/sushi/MainActivity.kt:500-667` — rebuild the
  parameter dialog with live preview and back navigation.
- `PlaysActivity` layout — hint text under the command field.

### Scope

Medium. The DB migration is the riskiest part; the dialog rework is
mostly UI plumbing.

---

## 4. Specific, recoverable connection errors

### Friction today

`SshClient.connect()` returns an `SshConnectResult(success, message)`
(`SshClient.kt:89-129`). Today nearly every failure surfaces as the same
generic toast — "Unable to connect. Check host and credentials." —
regardless of whether the cause was:

- DNS / network failure
- TCP timeout
- Auth failure (key rejected vs. password wrong)
- Host-key mismatch
- Jump-server failure
- Channel open failure after auth

`TerminalActivity.kt:150-165` then auto-retries once after a 1200 ms delay,
which can mask transient errors but also masks systematic ones (a wrong
password gets retried with the same wrong password). The user has no
recovery affordance other than tapping **Connect** again.

### Proposal

1. Extend `SshConnectResult` with a `reason: ConnectFailure` enum:
   `NETWORK`, `TIMEOUT`, `AUTH_KEY`, `AUTH_PASSWORD`, `HOST_KEY_MISMATCH`,
   `JUMP_FAILED`, `CHANNEL_FAILED`, `UNKNOWN`. Map JSch exceptions in
   `SshClient.connect()` to one of these.
2. Skip the auto-retry when the reason is `AUTH_*` or `HOST_KEY_MISMATCH`
   (retrying is pointless and, for host-key issues, dangerous).
3. Replace the toast with a small **persistent banner** in
   `TerminalActivity` that names the cause and offers a contextual action:

   | Reason | Action button |
   |--------|---------------|
   | `AUTH_KEY` | "Try password instead" |
   | `AUTH_PASSWORD` | "Edit credentials" → HostEditActivity |
   | `NETWORK` / `TIMEOUT` | "Retry" |
   | `HOST_KEY_MISMATCH` | "View host key" (no auto-accept) |
   | `JUMP_FAILED` | "Edit jump host" |

The banner stays until the user dismisses it or a new connection succeeds,
so the failure cannot be missed the way a toast can.

### Files involved

- `app/src/main/java/net/hlan/sushi/SshClient.kt` (lines 89-150) — exception
  classification, return new `reason`.
- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt` (lines 150-187) —
  remove blanket auto-retry, render banner, wire action buttons.
- `app/src/main/res/layout/activity_terminal.xml` — banner view.
- `strings.xml` — one entry per reason and action.

### Scope

Medium. The classification logic is the bulk of the work and is worth
unit-testing against representative `JSchException` messages.

---

## Suggested rollout order

1. **Connection errors (#4)** — foundational; better diagnostics make every
   other flow easier to support.
2. **First-run checklist (#2)** — biggest impact on new-user activation.
3. **Quick host switcher (#1)** — biggest impact on returning users.
4. **Play parameters (#3)** — schema migration; do last so the new error
   plumbing is already in place.
