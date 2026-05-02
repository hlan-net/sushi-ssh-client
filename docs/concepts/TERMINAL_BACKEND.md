# Concept: TerminalBackend

A small Kotlin interface that captures the contract `TerminalActivity` needs
from any interactive shell session — SSH today, local-shell tomorrow.
Everything else in the local-shell work hangs off this abstraction.

Related: [`HOST_KIND.md`](./HOST_KIND.md),
[`LOCAL_SHELL_PTY.md`](./LOCAL_SHELL_PTY.md),
[`HOST_SELECTION_UX.md`](./HOST_SELECTION_UX.md),
[`../ROADMAP_LOCAL_SHELL.md`](../ROADMAP_LOCAL_SHELL.md).

## Why an abstraction

`TerminalActivity` is wired directly to `SshClient` today
(`TerminalActivity.kt:29, 60, 73-86, 92-94, 170, 233, 290-308`). Adding a
second backend would force one of two unattractive shapes:

- **Fork the activity** — a `LocalTerminalActivity` that duplicates the
  retry loop, the resize forwarding, the input dedup, and the connection
  monitor. The bug-fix-once-per-place tax is real and immediate.
- **Type-check at every call site** — `if (sshClient != null) … else if
  (localBackend != null) …`. Every method gains a branch.

A thin interface is cheaper than either. `TerminalActivity` keeps one field,
one set of call sites, and one retry/monitor pipeline. The two backends differ
only in their constructor and in the body of `connect()`.

## The interface

New file `app/src/main/java/net/hlan/sushi/TerminalBackend.kt`. Captures only
the methods `TerminalActivity` actually calls — no aspirational surface.

```kotlin
package net.hlan.sushi

interface TerminalBackend {
    fun connect(
        onLine: (String) -> Unit,
        streamMode: Boolean = false,
        onConnectionClosed: (() -> Unit)? = null,
    ): SshConnectResult
    fun isConnected(): Boolean
    fun sendText(text: String): SshCommandResult
    fun sendCommand(command: String): SshCommandResult
    fun sendCtrlC()
    fun sendCtrlD()
    fun resizePty(col: Int, row: Int, widthPx: Int, heightPx: Int)
    fun disconnect()
}
```

The signatures are copied verbatim from `SshClient` so making `SshClient`
implement the interface is a one-line change (`SshClient.kt:71` gains
`: TerminalBackend`). No other body changes are needed in `SshClient`.

`SshConnectResult` and `SshCommandResult` already exist at `SshClient.kt:55-64`
and are reused as the interface return types. Renaming them to
`TerminalConnectResult` / `TerminalCommandResult` is a follow-up that touches
every call site and is not worth bundling into this branch.

## What stays out of the interface

`execCommand()` and `sftpUpload()` are SSH-specific:

- `execCommand()` uses a JSch exec channel
  (`SshClient.kt:275-352`) — there is no analogue in a local PTY session, where
  you would just use the shell or `Runtime.exec` directly. Used by
  `ConversationManager` (`ConversationManager.kt:191`) and
  `SettingsActivity`'s test-connection button (`SettingsActivity.kt:567`).
- `sftpUpload()` opens an SFTP channel — used by `ShareActivity` for
  share-to-host file uploads.

Both stay on `SshClient` only. The interface is the **PTY contract**, nothing
more.

## Holder rework

`SshConnectionHolder` (today: holds an `SshClient` and `SshConnectionConfig`)
becomes a session holder for any backend, but it keeps a parallel reference to
the concrete `SshClient` so the conversation path keeps working without
forcing `ConversationManager` onto the interface (the renaissance defers
that).

```kotlin
object SshConnectionHolder {
    private var activeBackend: TerminalBackend? = null
    private var activeSshClient: SshClient? = null   // non-null only when SSH
    private var activeHostConfig: SshConnectionConfig? = null
    // listeners unchanged

    fun setActiveConnection(backend: TerminalBackend, config: SshConnectionConfig) {
        activeBackend = backend
        activeSshClient = backend as? SshClient
        activeHostConfig = config
        notifyConnected()
    }
    fun getActiveBackend(): TerminalBackend? = activeBackend
    fun getActiveSshClient(): SshClient? = activeSshClient
    fun getActiveConfig(): SshConnectionConfig? = activeHostConfig
    fun isConnected(): Boolean = activeBackend?.isConnected() == true
    // clearActiveConnection() nulls all three
}
```

`MainActivity.kt:878` (the only conversation-init call site) switches from
`getActiveClient()` to `getActiveSshClient()`. When the active host is LOCAL,
the call returns `null` and `initializeConversation()` no-ops — that matches
the "Gemini deferred" decision.

Renaming the object to `TerminalSessionHolder` is cosmetic and is left for a
follow-up to keep this diff surgical.

## Renaissance hand-off

`ConversationManager`, `PlayRunner`, and `PersonaClient` deliberately stay
typed on `SshClient` through this work. The renaissance is when they move onto
`TerminalBackend` and define the plans-vs-results observability story. This
branch establishes the seam, not the migration.

## Critical files

- `app/src/main/java/net/hlan/sushi/TerminalBackend.kt` (new)
- `app/src/main/java/net/hlan/sushi/SshClient.kt` (line 71: implement
  interface; lines 55-64: shared result types)
- `app/src/main/java/net/hlan/sushi/SshConnectionHolder.kt` (full rewrite —
  small file)
- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt` (line 29 type, line
  290 helper signature)
- `app/src/main/java/net/hlan/sushi/MainActivity.kt` (line 878:
  `getActiveSshClient`)
