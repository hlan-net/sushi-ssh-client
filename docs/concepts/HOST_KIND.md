# Concept: HostKind

A discriminator on `SshConnectionConfig` so that a local-shell session can be
listed, selected, and persisted just like any other host. The user picks
"Local shell" from `HostsActivity` and gets a terminal — no special-case
screen, no separate flow.

Related: [`TERMINAL_BACKEND.md`](./TERMINAL_BACKEND.md),
[`LOCAL_SHELL_PTY.md`](./LOCAL_SHELL_PTY.md),
[`HOST_SELECTION_UX.md`](./HOST_SELECTION_UX.md),
[`../ROADMAP_LOCAL_SHELL.md`](../ROADMAP_LOCAL_SHELL.md).

## Why a discriminator on `SshConnectionConfig`

The least disruptive shape. Today `SshSettings.getHosts()`, `HostAdapter`, and
`HostEditActivity` all iterate `List<SshConnectionConfig>`
(`SshSettings.kt:36-43`, `HostAdapter.kt:15`, `HostEditActivity.kt:14`). A
parallel `LocalHostConfig` type would fork all three: two adapters, two
persisted lists, two pickers in `HostEditActivity` (jump-server selector takes
`SshConnectionConfig`).

A single field on the existing class costs one branch in the activity that
opens a session and a few field-visibility toggles in the editor. Everything
else — list rendering, persistence, jump-server selection — keeps working.

The class name `SshConnectionConfig` becomes mildly misleading once it can
also describe a local shell. Renaming to `HostConfig` is a follow-up; this
branch leaves the name alone to keep the diff focused.

## The enum and field

```kotlin
enum class HostKind { SSH, LOCAL }

data class SshConnectionConfig(
    val kind: HostKind = HostKind.SSH,   // default preserves old persisted data
    val id: String = java.util.UUID.randomUUID().toString(),
    val alias: String = "",
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val authPreference: String? = SshAuthPreference.AUTO.value,
    val privateKey: String? = null,
    val jumpEnabled: Boolean = false,
    val jumpHostId: String? = null,
    val jumpHost: String = "",
    val jumpPort: Int = 22,
    val jumpUsername: String = "",
    val jumpPassword: String = "",
)
```

When `kind == LOCAL`, the SSH-only fields (`host`, `port`, `username`, etc.)
are stored as empty/zero and ignored at runtime by the local backend.

## Persistence — no schema bump needed

`SshSettings` uses Moshi with `KotlinJsonAdapterFactory`
(`SshSettings.kt:10`). Kotlin defaults are honoured: a JSON document missing
the `kind` field deserialises with `kind = HostKind.SSH`, which is exactly the
historical behaviour. Enum values serialise by name (`"SSH"`, `"LOCAL"`).

**Verification path:** install the new build over an older one with persisted
SSH hosts; open `HostsActivity`; confirm those hosts show up unchanged. For
deeper verification, dump the prefs file:

```
adb shell run-as net.hlan.sushi cat files/...   # path varies by SecurePrefs
```

and confirm the new `kind` field is present after the next save and
old-format JSON without it still loads.

## Synthetic Local host

A single LOCAL-kind host is seeded on first launch. New method on
`SshSettings`:

```kotlin
fun seedLocalHostIfMissing() {
    if (getHosts().any { it.kind == HostKind.LOCAL }) return
    val local = SshConnectionConfig(
        kind = HostKind.LOCAL,
        alias = context.getString(R.string.local_shell_default_alias), // "Local shell"
        host = "", port = 0, username = "", password = "",
    )
    saveHost(local)
}
```

`SshSettings(context)` already takes a `Context` (`SshSettings.kt:8`) for
`SecurePrefs.get()`; store it as a field so `seedLocalHostIfMissing` can
resolve the string resource.

Called from `SushiApplication.onCreate()` so the host exists before any
activity runs. **Not** from `SettingsActivity.migrateOldSettingsIfNeeded()`
(`SshSettings.kt:115-141`), which only fires when settings are opened — wrong
hook for first-launch seeding.

The synthetic host:
- is editable for `alias` only (the user can rename it);
- cannot be deleted (the **Delete** button is hidden in `HostEditActivity`
  when `kind == LOCAL`);
- never has `kind` reassigned by the editor.

## `displayTarget()` extension

`SshConnectionConfig.displayTarget()` (`SshClient.kt:48-52`) is reused widely
— connect-button label, terminal status row, "connected to" log line, and
host-pickers across `ShareActivity` and `HostEditActivity`. Extending it to
include a kind suffix (`"Production · ssh"`, `"Local · local"`) gives every
caller the right label for free.

```kotlin
fun displayTarget(): String {
    val core = if (kind == HostKind.LOCAL) {
        alias.ifBlank { "Local shell" }
    } else if (alias.isNotBlank()) {
        "$alias ($username@$host:$port)"
    } else {
        "$username@$host:$port"
    }
    val kindLabel = if (kind == HostKind.LOCAL) "local" else "ssh"
    return "$core · $kindLabel"
}
```

This is what makes [`HOST_SELECTION_UX.md`](./HOST_SELECTION_UX.md)'s "active
host visible in `TerminalActivity`" requirement a no-op: the existing
connect-button label automatically reads `"End session: Production · ssh"` /
`"Start session: Local shell · local"`.

## HostEditActivity field visibility

`HostEditActivity.kt:65-83, 85-135` already gates fields by
`isEditMode`. Add a parallel gating by `kind`:

- When `kind == LOCAL`: hide `sshHostInput`, `sshPortInput`,
  `sshUsernameInput`, `sshPasswordInput`, `authPreferenceInput`,
  `jumpEnabledSwitch`, the entire jump section. Show only `hostAliasInput`.
- `saveHost()` (line 85): when `kind == LOCAL`, skip the host/username
  validation and persist with empty SSH fields.
- For new hosts (no `EXTRA_HOST_ID`), the editor only creates SSH hosts. Local
  is exclusively the synthetic instance — no UI for the user to add a second
  one.

## Critical files

- `app/src/main/java/net/hlan/sushi/SshClient.kt` (lines 14-53: enum + field
  + `displayTarget()` extension)
- `app/src/main/java/net/hlan/sushi/SshSettings.kt` (line 8: store context;
  add `seedLocalHostIfMissing()`; line 10: Moshi factory unchanged)
- `app/src/main/java/net/hlan/sushi/SushiApplication.kt` (call seed in
  `onCreate`)
- `app/src/main/java/net/hlan/sushi/HostEditActivity.kt` (lines 29-38, 65-83,
  85-135: kind-aware visibility and validation; hide delete for LOCAL)
- `app/src/main/res/values/strings.xml` (new
  `local_shell_default_alias`)
