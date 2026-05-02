# Concept: Local-shell PTY

The local-shell backend gives the user a real, full-featured shell on the
Android device itself. Real means a PTY: `vi`, `top`, `less`, `nano`,
`watch`, `ssh` from the device — all the things that need a controlling
terminal — actually work.

Related: [`TERMINAL_BACKEND.md`](./TERMINAL_BACKEND.md),
[`HOST_KIND.md`](./HOST_KIND.md),
[`HOST_SELECTION_UX.md`](./HOST_SELECTION_UX.md),
[`../ROADMAP_LOCAL_SHELL.md`](../ROADMAP_LOCAL_SHELL.md).

## Why not Termux's `terminal-emulator`

Termux ships a battle-tested PTY + emulator library, but it is **GPLv3**.
Linking it would require Sushi to relicense from Apache-style permissive to
GPL — a bigger decision than this branch should make. The reuse argument is
also weak: Sushi already renders with its own `TerminalView`, so it does not
need the emulator part. It needs only a PTY allocator: ~100 lines of C.

## Native lib `sushi-pty`

A small JNI shim that wraps `forkpty(3)` and the four operations we need on
the master fd. Lib name: `sushi-pty`, loaded via
`System.loadLibrary("sushi-pty")` from `LocalShellBackend`.

JNI surface (declared as `external` companion functions on
`LocalShellBackend`):

```
nativeStart(cmd: String, argv: Array<String>, envp: Array<String>): Long
    Returns an opaque handle (pointer to a malloc'd { master_fd, child_pid }
    struct, cast to jlong). 0 on failure.

nativeRead(handle: Long, buf: ByteArray): Int
    Blocking read from master_fd. Returns bytes read, or -1 on EOF/EIO so the
    Kotlin reader thread exits cleanly.

nativeWrite(handle: Long, data: ByteArray): Int
    write() loop on master_fd; handles EINTR. Returns bytes written, or -1.

nativeResize(handle: Long, col: Int, row: Int, widthPx: Int, heightPx: Int)
    ioctl(master_fd, TIOCSWINSZ, &winsize).

nativeClose(handle: Long)
    kill(child_pid, SIGHUP); waitpid(child_pid, NULL, 0); close(master_fd);
    free struct.
```

C implementation notes (in `app/src/main/cpp/sushi-pty.c`):

- `forkpty(&master_fd, NULL, NULL, NULL)` — creates the master/slave pair,
  forks, calls `setsid()` in the child, and assigns the slave as the
  controlling terminal.
- Set `FD_CLOEXEC` on `master_fd` so it does not leak into other forks.
- In the child, walk `envp` and `setenv()` each `KEY=VALUE` entry, then
  `execvp(cmd, argv)`. On failure write a marker to fd 2 and `_exit(127)`.
- `nativeRead`/`nativeWrite` loop on `EINTR`. Treat `EIO` as EOF (Linux
  reports `EIO` on master fd when the slave has been closed by the child
  exiting).
- `nativeClose` is idempotent and safe to call from a finalizer-ish path.
- Track exactly one allocation per session in the handle struct so `free()`
  is straightforward.

The whole file is small enough that it does not warrant being split. Link
only against `log` (optional, for `__android_log_print` warnings — fine to
omit).

## Kotlin side: `LocalShellBackend`

Mirrors the threading style of `SshClient.startShellReader()`
(`SshClient.kt:447-468`) so a reader of either backend feels familiar:

- A daemon `Thread` named `LocalShellReader` calls `nativeRead` in a loop,
  invokes `onLine` (or the streamed callback) with the decoded bytes, and
  exits when `nativeRead` returns -1.
- On exit, calls the `onConnectionClosed` callback so `TerminalActivity`'s
  monitor handler reacts the same way it does to an SSH disconnect.
- Writes use `Thread { ... }.start()` per the existing convention in
  `TerminalActivity.sendRaw` (line 232).
- `runCatching` around all native calls in cleanup paths.

```kotlin
class LocalShellBackend(private val context: Context) : TerminalBackend {
    private var nativeHandle: Long = 0L
    private var readerThread: Thread? = null

    override fun connect(
        onLine: (String) -> Unit,
        streamMode: Boolean,
        onConnectionClosed: (() -> Unit)?,
    ): SshConnectResult {
        val shell = System.getenv("SHELL").takeUnless { it.isNullOrBlank() }
            ?: "/system/bin/sh"
        val handle = nativeStart(shell, arrayOf(shell, "-i"), defaultEnv())
        if (handle == 0L) {
            return SshConnectResult(false, "PTY allocation failed")
        }
        nativeHandle = handle
        startReader(onLine, streamMode, onConnectionClosed)
        return SshConnectResult(true, "Connected to local shell")
    }

    override fun isConnected(): Boolean = nativeHandle != 0L
    override fun sendText(text: String): SshCommandResult { /* nativeWrite */ }
    override fun sendCommand(command: String): SshCommandResult {
        val payload = if (command.endsWith("\n")) command else "$command\n"
        return sendText(payload)
    }
    override fun sendCtrlC() { /* nativeWrite byte 3 */ }
    override fun sendCtrlD() { /* nativeWrite byte 4 */ }
    override fun resizePty(col: Int, row: Int, wp: Int, hp: Int) {
        if (nativeHandle != 0L) nativeResize(nativeHandle, col, row, wp, hp)
    }
    override fun disconnect() {
        val h = nativeHandle; nativeHandle = 0L
        if (h != 0L) runCatching { nativeClose(h) }
        readerThread = null
    }

    private fun defaultEnv(): Array<String> = listOf(
        "HOME=${System.getenv("HOME") ?: context.filesDir.absolutePath}",
        "PATH=${System.getenv("PATH") ?: "/system/bin:/system/xbin"}",
        "ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: "/data"}",
        "ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}",
        "TMPDIR=${context.cacheDir.absolutePath}",
        "TERM=xterm-256color",
    ).toTypedArray()

    companion object {
        init { System.loadLibrary("sushi-pty") }
        @JvmStatic external fun nativeStart(cmd: String, argv: Array<String>, envp: Array<String>): Long
        @JvmStatic external fun nativeRead(handle: Long, buf: ByteArray): Int
        @JvmStatic external fun nativeWrite(handle: Long, data: ByteArray): Int
        @JvmStatic external fun nativeResize(handle: Long, col: Int, row: Int, wp: Int, hp: Int)
        @JvmStatic external fun nativeClose(handle: Long)
    }
}
```

The `defaultEnv` allowlist is deliberately small: enough to feel like a real
shell, not so much that we leak surprising state from the app process. Pass
`Context` through so `cacheDir` and `filesDir` are reachable.

## Gradle wiring

`app/build.gradle.kts`:

```kotlin
android {
    // ...
    ndkVersion = "27.0.12077973"   // pin to a current stable; verify in CI
    defaultConfig {
        // ...
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake { /* nothing required here today */ }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

ABIs: `arm64-v8a` and `armeabi-v7a` cover real devices; `x86_64` is included
so emulator-based instrumented tests work (`./gradlew
connectedDebugAndroidTest` on a `x86_64` emulator).

`CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(sushi-pty C)

add_library(sushi-pty SHARED sushi-pty.c)
target_link_libraries(sushi-pty log)
```

## A note on `minSdk`

Actual `minSdk` is **26** per `app/build.gradle.kts:25`. `CLAUDE.md` says
"min SDK 24" but `CLAUDE.md` is stale; the Gradle file is the source of
truth. Do **not** lower `minSdk` for this work. minSdk 26 is fine for
`forkpty` — the NDK provides it on all supported ABIs.

## ProGuard

`app/proguard-rules.pro` additions:

```
# Native methods must keep their JNI-resolvable names.
-keepclasseswithmembernames class * { native <methods>; }

# LocalShellBackend's name is referenced by the JNI symbol naming convention
# (Java_net_hlan_sushi_LocalShellBackend_native*). Keep the class to be safe.
-keep class net.hlan.sushi.LocalShellBackend { *; }
```

The release build is minified. Verify with `./gradlew assembleRelease` then
install on device that `libsushi-pty.so` loads and the synthetic Local host
opens cleanly.

## TerminalActivity branching

`TerminalActivity.kt:110-127` (`connectTerminal()`) gains a single switch:

```kotlin
val config = sshSettings.getConfigOrNull() ?: ...
val newBackend: TerminalBackend = when (config.kind) {
    HostKind.SSH -> SshClient(config)
    HostKind.LOCAL -> LocalShellBackend(applicationContext)
}
```

The retry loop at `TerminalActivity.kt:131-145` only makes sense for network
failures. Guard it:

```kotlin
if (!result.success && config.kind == HostKind.SSH) {
    // existing retry block
}
```

`connectWithClient(config)` (line 290) becomes `connectWith(backend, config)`
and stops constructing `SshClient` itself — its caller already picked the
backend.

## Critical files

- `app/src/main/cpp/sushi-pty.c` (new)
- `app/src/main/cpp/CMakeLists.txt` (new)
- `app/src/main/java/net/hlan/sushi/LocalShellBackend.kt` (new)
- `app/build.gradle.kts` (NDK + cmake + abiFilters)
- `app/proguard-rules.pro` (keep native methods + `LocalShellBackend`)
- `app/src/main/java/net/hlan/sushi/TerminalActivity.kt` (lines 110-127:
  kind branching; lines 131-145: SSH-only retry guard; lines 290-308:
  helper signature)
