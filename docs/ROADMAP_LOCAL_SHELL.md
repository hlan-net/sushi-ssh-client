# Roadmap: Local-shell terminal + host-selection rework

Phased implementation plan for adding a local-shell terminal to Sushi and
fixing the host-selection UX along the way. The *what* and *why* live in the
concept docs under [`./concepts/`](./concepts/) — this file is the *how* and
*when*.

## Goal

Ship a local-shell terminal on the Android device with a real PTY (so `vi`,
`top`, `less` work), surfaced as just another host in the existing host list.
Reduce the taps-to-connection cost for known hosts from 6–8 down to 1.

**Out of scope:** PlayRunner and Gemini wiring against the local backend —
held for the future "renaissance" where Gemini sits on top of the terminal
abstraction and emphasises observability of plans-vs-results.

## Concepts

The implementation is structured around four concepts. Read them before
starting the corresponding branch:

- [`./concepts/TERMINAL_BACKEND.md`](./concepts/TERMINAL_BACKEND.md) — the
  Kotlin interface that captures the PTY contract shared by SSH and local
  sessions.
- [`./concepts/HOST_KIND.md`](./concepts/HOST_KIND.md) — the
  `HostKind { SSH, LOCAL }` discriminator on `SshConnectionConfig`, the
  synthetic Local host seeded on first launch, and the `displayTarget()`
  extension that gives every label the kind suffix for free.
- [`./concepts/LOCAL_SHELL_PTY.md`](./concepts/LOCAL_SHELL_PTY.md) — the
  `LocalShellBackend` and its small JNI shim around `forkpty()`. Includes the
  Gradle/NDK/ProGuard wiring and the rationale for not adopting Termux's
  GPL-licensed emulator library.
- [`./concepts/HOST_SELECTION_UX.md`](./concepts/HOST_SELECTION_UX.md) — the
  four targeted UX fixes (one-tap connect, drop silent auto-select, settings
  dedup, kind-aware connect-button label).

## Dependency graph

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

The branches are sequential:

- Branch 2 depends on the `TerminalBackend` interface from Branch 1 — without
  it, `LocalShellBackend` has nothing to plug into.
- Branch 3's connect-button kind suffix needs the `displayTarget()` extension
  from Branch 2. The other three Branch 3 fixes (one-tap connect, drop
  auto-select, settings dedup) are independent and could ship sooner if the
  team chooses, but bundling them keeps the UX story coherent in one
  release.

Each branch passes through SonarQube before merge and may cut a release on
main.

---

## Branch 1 — TerminalBackend abstraction

**User-visible change:** none. This is a refactor that establishes the seam
for Branch 2.

**Edits:**

- New file `app/src/main/java/net/hlan/sushi/TerminalBackend.kt` — the
  interface.
- `app/src/main/java/net/hlan/sushi/SshClient.kt:71` — add `: TerminalBackend`
  to the class declaration. Existing method signatures already match.
- `app/src/main/java/net/hlan/sushi/SshConnectionHolder.kt` — full rewrite
  (small file). Holds both `TerminalBackend?` (primary) and `SshClient?`
  (legacy, non-null only when the active backend is SSH). Adds
  `getActiveBackend()` and `getActiveSshClient()` accessors.
- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt:29` — change field
  type to `TerminalBackend?`. Line 290 helper `connectWithClient(config)`
  changes its return type to `Pair<TerminalBackend, SshConnectResult>`.
- `app/src/main/java/net/hlan/sushi/MainActivity.kt:878` — switch from
  `SshConnectionHolder.getActiveClient()` to `getActiveSshClient()`.
- `ConversationManager`, `PlayRunner`, `PersonaClient`, `ShareActivity`,
  `SettingsActivity` SSH test path, and `LocalSshIntegrationTest` are
  **untouched** — they keep typing on `SshClient` directly.

**Verify:**

- `./gradlew testDebugUnitTest` — existing tests pass with `SshClient`
  implementing `TerminalBackend`.
- `./gradlew lint` clean (or only the existing baseline lint).
- `./scripts/install-wifi-debug.sh` to a real device. Open an existing SSH
  host: connect, type, send Ctrl+C, rotate the screen (resize), disconnect.
  Behaviour identical to today.

---

## Branch 2 — Local backend + JNI PTY

**User-visible change:** a new "Local shell" host appears in `HostsActivity`.
Tapping it opens a real PTY shell on the device. Existing SSH hosts behave
unchanged.

**Edits:**

- `app/src/main/java/net/hlan/sushi/SshClient.kt` (lines 14-53) — add
  `enum class HostKind { SSH, LOCAL }` and the `kind: HostKind = HostKind.SSH`
  field on `SshConnectionConfig`. Extend `displayTarget()` to append the
  kind suffix.
- `app/src/main/java/net/hlan/sushi/SshSettings.kt` — store `Context` as a
  field; add `seedLocalHostIfMissing()`.
- `app/src/main/java/net/hlan/sushi/SushiApplication.kt` — call
  `SshSettings(this).seedLocalHostIfMissing()` in `onCreate`.
- New file `app/src/main/java/net/hlan/sushi/LocalShellBackend.kt`.
- New file `app/src/main/cpp/sushi-pty.c`.
- New file `app/src/main/cpp/CMakeLists.txt`.
- `app/build.gradle.kts` — `ndkVersion`, `externalNativeBuild { cmake { ... } }`,
  `defaultConfig.ndk.abiFilters`.
- `app/proguard-rules.pro` — keep native methods and `LocalShellBackend`.
- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt:110-127` — branch on
  `config.kind` to construct the right backend; guard the SSH retry loop
  (lines 131-145) with `if (config.kind == HostKind.SSH)`.
- `app/src/main/java/net/hlan/sushi/HostEditActivity.kt` — hide SSH-only
  fields when `kind == LOCAL`; disable **Delete** for the synthetic Local
  host; skip host/username validation for LOCAL.
- `app/src/main/res/values/strings.xml` — new strings:
  `local_shell_default_alias`, kind labels.

**Verify:**

- New unit test `app/src/test/java/net/hlan/sushi/LocalShellBackendTest.kt`:
  start backend, write `echo hello\n`, assert `"hello"` appears in `onLine`,
  disconnect cleanly, exit code = 0. If JNI loading is awkward in JVM unit
  tests, promote to `app/src/androidTest/`.
- New instrumented test path in `app/src/androidTest/`: open the synthetic
  Local host → `TerminalActivity` connects → run `ls /` (assert output
  contains `system`) → run `vi /tmp/x`, type `:q!`, exit cleanly (asserts a
  real PTY) → disconnect.
- `./gradlew assembleRelease` then install — confirm the minified release
  loads `libsushi-pty.so` and that the synthetic Local host opens (i.e.
  ProGuard did not strip JNI methods or the `LocalShellBackend` class).
- `./gradlew connectedDebugAndroidTest` on an `x86_64` emulator — confirms
  ABI coverage.
- Real-device regression of all three SSH user flows (read+copy a file,
  restart a service, run a maintenance script).
- Persistence check: install over an older build that has saved SSH hosts.
  Confirm those hosts deserialise as `kind = SSH`. Inspect with
  `adb shell run-as net.hlan.sushi cat files/...` if needed.
- `./scripts/run-device-qa-suite.sh` clean.

---

## Branch 3 — Host-selection UX

**User-visible change:** tapping a host in `HostsActivity` opens a connected
session in one tap. Settings → SSH no longer shows duplicate Manage Hosts /
Quick Add Host buttons. Saving a new host no longer silently activates it.

**Edits:**

- `app/src/main/java/net/hlan/sushi/HostsActivity.kt:20-31` — `onHostClick`
  becomes `setActiveHostId(host.id)` + `startActivity(TerminalActivity.createIntent(this, autoConnect = true))` + `finish()`.
- `app/src/main/java/net/hlan/sushi/HostEditActivity.kt:128-131` — delete the
  silent first-host auto-select block.
- `app/src/main/java/net/hlan/sushi/SettingsActivity.kt:196-205` — drop the
  click listeners for `manageHostsButton` and `quickAddHostButton`.
- `app/src/main/res/layout/page_settings_ssh.xml` — delete `quickAddHostButton`
  (lines 194-202) and `manageHostsButton` (lines 216-224). Keep the summary
  card and `quickGenerateKeyButton`.
- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt` — no edits required;
  the connect-button label inherits the kind suffix from
  `displayTarget()` (delivered in Branch 2).

**Verify:**

- Manual click-path on a real device (run for both an SSH host and the
  synthetic Local host): tap the host in `HostsActivity` → terminal opens
  *connected*, no detour through `MainActivity`.
- Connect-button label shows the kind suffix
  (`"End session: <alias> · ssh|local"`).
- Settings → SSH no longer has Manage Hosts / Quick Add Host buttons; the
  summary card still renders host/auth/Gemini/Drive lines.
- Save a brand new SSH host via `HostEditActivity` while a different host is
  already active → confirm the active host did **not** silently change.
- `./scripts/run-device-qa-suite.sh` clean.

---

## Cross-branch checks

- SonarQube run on each branch before merge.
- A release is cut on `main` after each branch lands (per the team's
  ship-fast convention; do not batch).
- `./scripts/run-device-qa-suite.sh` is the gate for every branch.
- Persisted-data backwards compatibility is verified once during Branch 2
  (the schema change) and re-verified on Branch 3 (no schema change, but
  the editor now persists hosts under different conditions).

## Renaissance hand-off

`ConversationManager`, `PlayRunner`, and `PersonaClient` deliberately keep
typing on `SshClient` through this work. The renaissance moves them onto
`TerminalBackend`, decides what `execCommand`/`sftpUpload` look like for the
local backend (probably `Runtime.exec` and a no-op respectively), and defines
the plans-vs-results observability story that the user has flagged as the
real reason for the Gemini overhaul. None of that belongs in this roadmap.
