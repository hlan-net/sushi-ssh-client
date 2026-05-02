# Local-shell terminal + host-selection rework — docs & roadmap

## Context

Sushi is currently SSH-only. We want a **local-shell terminal** on the Android
device with a real PTY (so `vi`/`top`/`less` work), surfaced as just another
host in the existing host list. While auditing the wiring it became obvious the
host-selection UX is doing too much work: connecting to a known host takes 6–8
taps because *picking* a host (`HostsActivity`) and *opening a session*
(`MainActivity` → `TerminalActivity` → tap **Start session**) are split across
three screens.

**Plays and Gemini are explicitly out of scope** — the user has flagged them
for a future "renaissance". This pass establishes the foundation only.

This planning step produces **documentation only** — no source-code changes
yet. The deliverable is:

1. Four concept docs under `docs/concepts/` capturing the *what* and *why*
2. One roadmap doc under `docs/` capturing the *how* and *when* (phased
   implementation)

The existing `docs/` layout (e.g. `docs/USER_STORIES.md`,
`docs/UX_FLOWS.md`, `docs/FLOW_IMPROVEMENTS.md`) sets the convention: per-topic
markdown files at the docs root. We add a `docs/concepts/` subdirectory because
we are introducing four genuinely separate concepts that benefit from being
linkable individually.

## Deliverable layout

```
docs/
├── concepts/
│   ├── TERMINAL_BACKEND.md      ← abstraction contract
│   ├── HOST_KIND.md             ← SSH/LOCAL discriminator + synthetic host
│   ├── LOCAL_SHELL_PTY.md       ← JNI shim + native lib design
│   └── HOST_SELECTION_UX.md     ← one-tap connect, dedup, active-host display
└── ROADMAP_LOCAL_SHELL.md       ← phased implementation plan
```

Each concept doc is self-contained (problem, design, alternatives considered,
critical files referenced). The roadmap depends on all four — it sequences
their implementation across three branches and lists the verify-before-merge
steps for each.

---

## Shape of the change (preview — full detail in concept docs)

```
┌──────────────────────────────────────────────────────────────────┐
│ Branch 1 — TerminalBackend abstraction (no user-visible change)  │
│   SshClient ──implements──▶ TerminalBackend                      │
│   SshConnectionHolder ─▶ TerminalBackend  (+ legacy SshClient?)  │
│   ConversationManager: unchanged (renaissance defers it)         │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ Branch 2 — Local backend + JNI PTY                               │
│   HostKind {SSH, LOCAL} on SshConnectionConfig (default SSH;     │
│       Moshi Kotlin defaults handle old persisted JSON)           │
│   LocalShellBackend ──JNI──▶ libsushi-pty.so (forkpty,           │
│                              ~100 LOC C, no GPL dependency)      │
│   Synthetic Local host seeded on first launch                    │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ Branch 3 — Host-selection UX                                     │
│   HostsActivity row tap ──▶ TerminalActivity.createIntent(       │
│                                  autoConnect = true)             │
│   Drop silent first-host auto-select; dedup Settings buttons     │
└──────────────────────────────────────────────────────────────────┘
```

---

## File-by-file: what to write

### `docs/concepts/TERMINAL_BACKEND.md`

**Purpose:** define the PTY contract that both SSH and local sessions
implement. Everything else in this work hangs off this abstraction.

**Sections:**
- *Why an abstraction* — `TerminalActivity` today is wired directly to
  `SshClient` (`TerminalActivity.kt:29, 60, 73-86, 92-94, 170, 233, 290-308`).
  A second backend would either fork the activity or hide behind type checks.
  An interface is cheaper.
- *The interface* (verbatim Kotlin, captures only the methods
  `TerminalActivity` actually calls — `connect`, `isConnected`, `sendText`,
  `sendCommand`, `sendCtrlC`, `sendCtrlD`, `resizePty`, `disconnect`).
- *What stays out of the interface* — `execCommand()` and `sftpUpload()` are
  SSH-specific and used by `ConversationManager` (`ConversationManager.kt:191`),
  `SettingsActivity`'s test button (`SettingsActivity.kt:567`), and
  `ShareActivity`. They remain on `SshClient` only.
- *Holder rework* — `SshConnectionHolder` keeps both
  `getActiveBackend(): TerminalBackend?` and `getActiveSshClient(): SshClient?`
  so the conversation path keeps working without forcing
  `ConversationManager` onto the interface (renaissance defers it).
- *Reuse* — keep existing `SshConnectResult` and `SshCommandResult`
  (`SshClient.kt:55-64`) as the interface return types; renaming is a follow-up.
- *Critical files referenced:* `SshClient.kt:71`, `SshConnectionHolder.kt`,
  `TerminalActivity.kt:29,290`, `MainActivity.kt:878`.

### `docs/concepts/HOST_KIND.md`

**Purpose:** add a discriminator so a local shell appears as just another host.

**Sections:**
- *Why a discriminator on `SshConnectionConfig`* (Option A) — least disruptive;
  `SshSettings`, `HostAdapter`, `HostEditActivity` already iterate
  `List<SshConnectionConfig>`. A parallel `LocalHostConfig` would fork all
  three.
- *The enum and field*:
  ```kotlin
  enum class HostKind { SSH, LOCAL }
  data class SshConnectionConfig(
      val kind: HostKind = HostKind.SSH,   // default preserves old data
      // ...
  )
  ```
- *Persistence migration* — Moshi's `KotlinJsonAdapterFactory`
  (`SshSettings.kt:10`) honours Kotlin defaults; old persisted JSON
  deserialises with `kind = SSH` automatically. **No schema bump.**
- *Synthetic Local host* — seeded on first app launch via new
  `SshSettings.seedLocalHostIfMissing()`, called from
  `SushiApplication.onCreate()` (not from `SettingsActivity`'s existing
  `migrateOldSettingsIfNeeded()` — that only runs when settings are opened).
  Synthetic host alias defaults to `R.string.local_shell_default_alias`
  ("Local shell"), is editable for alias only, and cannot be deleted.
- *`displayTarget()` extension* — append a kind suffix so the existing
  connect-button label, terminal status row, and "connected to" log line all
  gain the indicator without new wiring.
- *HostEditActivity field visibility* — when `kind == LOCAL`, hide
  host/port/username/password/auth/jump fields; show only alias.
- *Critical files referenced:* `SshClient.kt:14-53`, `SshSettings.kt:10,91-141`,
  `HostEditActivity.kt:65-83,85-135`, `SushiApplication.kt`,
  `app/src/main/res/values/strings.xml`.

### `docs/concepts/LOCAL_SHELL_PTY.md`

**Purpose:** specify the local backend and the native PTY shim.

**Sections:**
- *Why not Termux's `terminal-emulator`* — GPLv3, would force Sushi to GPL.
  Sushi already has its own `TerminalView`; it only needs a PTY allocator,
  which is ~100 LOC of C.
- *Native lib `sushi-pty`* — JNI surface:
  ```
  nativeStart(cmd: String, argv: String[], envp: String[]): Long  // master fd handle
  nativeWrite(handle: Long, data: ByteArray): Int
  nativeRead(handle: Long, buf: ByteArray): Int                   // blocking
  nativeResize(handle: Long, col, row, wp, hp)
  nativeClose(handle: Long)
  ```
  Implementation notes: `forkpty(&master_fd, NULL, NULL, NULL)`,
  `FD_CLOEXEC` on master, `setenv()` from envp in child, `execvp()`,
  `_exit(127)` on exec failure, `ioctl(TIOCSWINSZ)` for resize, `kill(SIGHUP)
  + waitpid()` on close. Loop on EINTR for read/write.
- *Kotlin side* — `LocalShellBackend.kt` mirrors the threading style of
  `SshClient.startShellReader()` (`SshClient.kt:447-468`): daemon `Thread`
  named `LocalShellReader`, blocking `nativeRead`, `runCatching` cleanup on
  disconnect. Writes use `Thread { ... }.start()` per existing convention in
  `TerminalActivity.sendRaw` (line 232).
- *Default shell* — `System.getenv("SHELL") ?: "/system/bin/sh"`. Args
  `["-i"]`. Env allowlist: `HOME`, `PATH`, `ANDROID_DATA`, `ANDROID_ROOT`,
  `TMPDIR=context.cacheDir.absolutePath`, `TERM=xterm-256color`.
- *Gradle wiring* — `externalNativeBuild` with CMake; `ndkVersion` pinned;
  `abiFilters = arm64-v8a, armeabi-v7a, x86_64` (x86_64 included for
  emulator-based instrumented tests).
- *Note on minSdk* — actual `minSdk = 26` per `app/build.gradle.kts:25`;
  CLAUDE.md saying "min SDK 24" is stale. minSdk 26 is fine for `forkpty` (NDK
  provides it).
- *ProGuard* — `-keepclasseswithmembernames class * { native <methods>; }` and
  `-keep class net.hlan.sushi.LocalShellBackend { *; }`.
- *TerminalActivity branching* — `connectTerminal()` selects backend by
  `config.kind`; SSH-only retry logic guarded by
  `if (config.kind == HostKind.SSH)`.
- *Critical files referenced:* `app/src/main/cpp/sushi-pty.c` (new),
  `app/src/main/cpp/CMakeLists.txt` (new),
  `app/src/main/java/net/hlan/sushi/LocalShellBackend.kt` (new),
  `app/build.gradle.kts`, `app/proguard-rules.pro`, `TerminalActivity.kt:110-127,290-308`.

### `docs/concepts/HOST_SELECTION_UX.md`

**Purpose:** capture the four targeted UX fixes (no full redesign).

**Sections:**
- *Friction today* (cite `FLOW_IMPROVEMENTS.md` for prior analysis): tapping a
  host in `HostsActivity` only marks it active and finishes; user must then
  navigate to `TerminalActivity` and tap **Start session**. 6–8 taps to
  connect.
- *Fix 1: One-tap connect* — `HostsActivity.kt:20-31` `onHostClick` now calls
  `setActiveHostId()` + `startActivity(TerminalActivity.createIntent(this,
  autoConnect = true))` + `finish()`. The `createIntent(autoConnect)` factory
  already exists at `TerminalActivity.kt:323`; no `TerminalActivity` change
  needed.
- *Fix 2: Active host visible in TerminalActivity* — `updateUi()` already
  reads `displayTarget()` for the connect-button label
  (`TerminalActivity.kt:270`). Once Branch 2 extends `displayTarget()` with the
  kind suffix, the label automatically shows e.g. "End session: Production ·
  ssh". Verify on device whether more is needed before adding new widgets.
- *Fix 3: Drop silent first-host auto-select* — delete
  `HostEditActivity.kt:128-131`. After save, user returns to `HostsActivity`
  and explicitly taps to activate-and-connect (now one tap thanks to Fix 1).
- *Fix 4: Settings dedup* — drop `manageHostsButton` and `quickAddHostButton`
  from `page_settings_ssh.xml` (lines 194-202, 216-224) and their click
  listeners in `SettingsActivity.kt:199-205`. Keep the read-only summary card
  and `quickGenerateKeyButton`. `MainActivity`'s `configureHostButton` remains
  the single entry point to host management.
- *Out of scope (deferred):* in-terminal host switcher, jump-server-as-host-
  reference refactor, home-screen redesign beyond the four bullets above.
- *Critical files referenced:* `HostsActivity.kt:20-31`,
  `HostEditActivity.kt:128-131`, `SettingsActivity.kt:196-205`,
  `app/src/main/res/layout/page_settings_ssh.xml:194-224`,
  `TerminalActivity.kt:323`.

### `docs/ROADMAP_LOCAL_SHELL.md`

**Purpose:** sequence the implementation across three branches with explicit
verify steps and a single-source-of-truth dependency graph.

**Sections:**
- *Goal* — one paragraph: ship local-shell terminal + reduce
  taps-to-connection. Out of scope: PlayRunner/Gemini wiring against local
  backend.
- *Concepts referenced* — bullet links to the four `docs/concepts/*.md` files,
  one line each summarising what they define.
- *Dependency graph* — the three-block diagram from this plan; explains why
  the branches are sequential (Branch 2 needs the interface from Branch 1;
  Branch 3 needs `displayTarget()`'s kind suffix from Branch 2 for the status
  label, but its other fixes are independent and could ship sooner if
  desired).
- *Branch 1 — TerminalBackend abstraction* — list of edits with file paths
  and line ranges:
  - new file `TerminalBackend.kt`
  - `SshClient.kt:71` add `: TerminalBackend`
  - `SshConnectionHolder.kt` add backend/sshClient fields and accessors
  - `TerminalActivity.kt:29,290` change types
  - `MainActivity.kt:878` switch to `getActiveSshClient()`
  - **Verify:** `./gradlew testDebugUnitTest` + `./gradlew lint` +
    `./scripts/install-wifi-debug.sh` then manual SSH session.
- *Branch 2 — Local backend* — list of edits with file paths:
  - `SshClient.kt` add `HostKind` + `kind` field + extend `displayTarget()`
  - new files `LocalShellBackend.kt`, `cpp/sushi-pty.c`, `cpp/CMakeLists.txt`
  - `app/build.gradle.kts` NDK + cmake + abiFilters
  - `app/proguard-rules.pro` keep native methods + LocalShellBackend
  - `SshSettings.kt` `seedLocalHostIfMissing()`; `SushiApplication.kt` calls it
  - `TerminalActivity.kt:110-127` kind branching
  - `HostEditActivity.kt` field-visibility toggle by kind
  - `app/src/main/res/values/strings.xml` new strings
  - **Verify:** new `LocalShellBackendTest`; instrumented test opening
    Local host, running `ls /` and `vi`; `./gradlew assembleRelease` to
    confirm minified release loads `libsushi-pty.so`; existing SSH flows
    (read+copy, restart service, run script) regression-tested; persistence
    check via `adb shell run-as net.hlan.sushi cat files/...`.
- *Branch 3 — Host-selection UX* — list of edits with file paths:
  - `HostsActivity.kt:20-31` one-tap connect
  - `HostEditActivity.kt:128-131` delete silent auto-select
  - `SettingsActivity.kt:199-205` drop button wiring
  - `app/src/main/res/layout/page_settings_ssh.xml:194-224` delete buttons
  - **Verify:** manual click-path on device (SSH and Local); kind suffix
    visible on connect button; settings page no longer shows Manage Hosts
    buttons; saving a new host does not silently change the active host;
    `./scripts/run-device-qa-suite.sh`.
- *Cross-branch checks* — SonarQube on each branch before merge; release cut
  on main after each branch lands; `./scripts/run-device-qa-suite.sh` clean
  on every branch.
- *Renaissance hand-off* — explicit note that `ConversationManager`,
  `PlayRunner`, and `PersonaClient` deliberately stay typed on `SshClient`
  through this work; the renaissance moves them onto `TerminalBackend` and
  defines the plans-vs-results observability story.

## Reuse callouts (cite in concept docs)

- `TerminalActivity.createIntent(context, autoConnect)` already at
  `TerminalActivity.kt:323`, used by `MainActivity:111,115`. Branch 3 calls it
  from `HostsActivity`.
- `SshConnectionConfig.displayTarget()` already at `SshClient.kt:48`. Branch
  2 extends it; Branch 3 inherits the kind label for free.
- `SshConnectResult` / `SshCommandResult` already at `SshClient.kt:55-64`;
  reused as interface return types.
- `SshSettings.getConfigOrNull()` (`SshSettings.kt:91-95`) already returns the
  active host config; unchanged.
- Moshi `KotlinJsonAdapterFactory` already configured in `SshSettings.kt:10`,
  so the new `kind` field with a Kotlin default deserialises old JSON cleanly.

## Verification (of this docs deliverable)

- `tree docs/concepts` shows the four `.md` files; `ls docs/ROADMAP_*.md`
  shows the roadmap.
- Each concept doc opens with a one-paragraph "what this is" and ends with a
  "critical files" list whose paths/lines all resolve in the current tree.
- The roadmap's branch sections each list edits with concrete file paths and
  a verify command/test that an implementer can run.
- All four concept docs link back to each other and to the roadmap; the
  roadmap links to each concept doc. Cross-links use relative paths
  (`./concepts/TERMINAL_BACKEND.md` from the roadmap).
- `git grep -n "FIXME\|TBD"` returns no hits in the new files.
