# Concept: Host-selection UX

Four targeted fixes to remove the friction the user described as "bizarre and
overly labored." This is **not** a home-screen redesign — it cuts the most
expensive taps and removes one source of hidden state changes. The broader
in-terminal switcher and home-screen overhaul are deferred.

Related: [`TERMINAL_BACKEND.md`](./TERMINAL_BACKEND.md),
[`HOST_KIND.md`](./HOST_KIND.md),
[`LOCAL_SHELL_PTY.md`](./LOCAL_SHELL_PTY.md),
[`../ROADMAP_LOCAL_SHELL.md`](../ROADMAP_LOCAL_SHELL.md). See also the prior
audit at [`../FLOW_IMPROVEMENTS.md`](../FLOW_IMPROVEMENTS.md).

## Friction today

To open a session with a known host, the user does roughly the following:

1. `MainActivity` → tap **Configure host** (or **Settings → SSH → Manage
   Hosts**).
2. `HostsActivity` → tap a host. The activity calls
   `setActiveHostId()` and `finish()`s. **Nothing else happens.** The user
   is back at `MainActivity`.
3. `MainActivity` → tap **Start session** (or **Return to terminal**).
4. `TerminalActivity` → tap **Start session** (auto-connect already runs in
   most cases, but in some paths the user lands disconnected and must tap).

That is two activities and 4–6 taps to reach a connected shell on a host the
user has used many times. The split between *picking* and *opening* is an
artefact, not a feature — pick is implicit in the user's intent to open.

A second source of friction: saving a brand new host in `HostEditActivity`
silently flips the active host to the new one (`HostEditActivity.kt:128-131`)
if no host was active before. There is no toast or banner, the user just
discovers later that their session is going somewhere unexpected.

A third: `SettingsActivity` → SSH tab has its own **Manage Hosts** and **Quick
Add Host** buttons (`page_settings_ssh.xml:194-224`,
`SettingsActivity.kt:199-205`). They duplicate `HostsActivity`'s reason to
exist and create two paths to the same screens. The summary card on the same
tab is useful — it stays. The buttons go.

## Fix 1 — One-tap connect from `HostsActivity`

`HostsActivity.kt:20-31` `onHostClick` becomes:

```kotlin
adapter = HostAdapter(
    onHostClick = { host ->
        sshSettings.setActiveHostId(host.id)
        startActivity(TerminalActivity.createIntent(this, autoConnect = true))
        finish()
    },
    onEditClick = { host ->
        startActivity(Intent(this, HostEditActivity::class.java).apply {
            putExtra(HostEditActivity.EXTRA_HOST_ID, host.id)
        })
    },
)
```

`TerminalActivity.createIntent(context, autoConnect)` already exists at
`TerminalActivity.kt:323` and is already used by `MainActivity:111,115`.
`EXTRA_AUTO_CONNECT` is wired at `TerminalActivity.kt:99-101`. **No
`TerminalActivity` change is required for this fix.**

Result: tapping a host opens a connected session. One tap, one screen.

## Fix 2 — Active host visible in `TerminalActivity`

`TerminalActivity.updateUi()` already reads `displayTarget()` for the
connect-button label (`TerminalActivity.kt:270-277`). Once
[`HOST_KIND.md`](./HOST_KIND.md)'s `displayTarget()` extension lands, the
label automatically reads e.g. `"End session: Production · ssh"` /
`"Start session: Local shell · local"`.

That is enough to answer the "where am I about to connect / where am I
connected" question. **Verify on device** before adding new widgets — it may
already be enough. If not, the next step is a small status row above the
output, fed by the same `displayTarget()` value; do not invent a new field.

## Fix 3 — Drop silent first-host auto-select

Delete `HostEditActivity.kt:128-131`:

```kotlin
// Auto-select if it's the first host or just created
if (sshSettings.getActiveHostId() == null) {
    sshSettings.setActiveHostId(config.id)
}
```

After save, the user returns to `HostsActivity` (the editor's `finish()` at
line 134). Activating the new host is now a one-tap action thanks to Fix 1,
so there is no UX cost to making it explicit. The win is no hidden state
changes.

A first-time user sees the empty-state copy in `HostsActivity` once, taps
**Add host**, fills in fields, taps **Save**, lands back in `HostsActivity`
with the new host visible, taps it, and is in a session. Five taps total,
zero of them surprising.

## Fix 4 — Settings dedup

`app/src/main/res/layout/page_settings_ssh.xml`:

- Delete `quickAddHostButton` (lines 194-202).
- Delete `manageHostsButton` (lines 216-224).
- Keep `settingsSummaryCard` (lines 29-149) — the read-only host/auth/Gemini/
  Drive summary stays; it answers "what's set up" without inviting
  navigation.
- Keep `quickGenerateKeyButton` and the `testConnectionButton` /
  `copyConnectionDiagnosticsButton` cluster — those are SSH-tab-native, not
  duplicates.

`SettingsActivity.kt:196-205` (`setupSshPage`): delete the two click
listeners for `pageBinding.manageHostsButton` and
`pageBinding.quickAddHostButton`. Leave the rest of the function intact.

Single entry point to host management is now `MainActivity`'s
`configureHostButton` (`MainActivity.kt:118-125`). It opens `HostEditActivity`
when the host list is empty (first-run) and `HostsActivity` otherwise — that
heuristic is correct; keep it.

## Out of scope (deferred)

- **In-terminal host switcher** — the dropdown idea from
  `FLOW_IMPROVEMENTS.md`. Worth doing later, but adds widgets and state to
  `TerminalActivity` and is not required to remove the current pain.
- **Jump-server-as-host-reference refactor** — the editor still mirrors jump
  fields onto the dependent host. Cleaning that up is its own concept doc.
- **Home-screen redesign** — beyond removing the duplicate Settings buttons,
  `MainActivity`'s top section is unchanged.
- **Active-host indicator in `MainActivity`** — `displayTarget()`'s kind
  suffix could land here too, but `MainActivity` already shows the host name
  on the **Return to terminal** button; deferring until users ask.

## Critical files

- `app/src/main/java/net/hlan/sushi/HostsActivity.kt` (lines 20-31: one-tap
  connect)
- `app/src/main/java/net/hlan/sushi/HostEditActivity.kt` (lines 128-131:
  delete silent auto-select)
- `app/src/main/java/net/hlan/sushi/SettingsActivity.kt` (lines 196-205:
  drop button wiring)
- `app/src/main/res/layout/page_settings_ssh.xml` (lines 194-202, 216-224:
  delete buttons)
- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt` (line 270:
  inherits the kind suffix from `displayTarget()`)
- `app/src/main/java/net/hlan/sushi/MainActivity.kt` (lines 118-125:
  unchanged — single entry point for host management)
