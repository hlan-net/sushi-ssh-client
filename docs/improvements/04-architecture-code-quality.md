# Architecture & code quality improvements

## 1. God activities (P1)

**Finding.** Line counts tell the story:

| File | Lines |
|------|-------|
| `MainActivity.kt` | 1230 |
| `SettingsActivity.kt` | 746 |
| `ConversationManager.kt` | 473 |
| `TerminalActivity.kt` | 413 |

`MainActivity` owns ViewPager2 page binding, terminal session wiring, Gemini
dialog control, play execution UI, and the setup checklist. Every feature
touches it, so every PR conflicts in it and nothing in it is unit-testable.

**Proposal.** Stay within the project's "no MVVM" convention but extract
**page controllers**: `TerminalPageController`, `PlaysPageController`,
`GeminiDialogController` — plain classes taking `(binding, lifecycleScope,
dependencies)` — so `MainActivity.onCreate` shrinks to constructing and wiring
them. Same pattern for `SettingsActivity`'s four pages. No framework needed;
this is pure code motion plus interface hygiene, done one controller per PR.

**Effort.** ~1–2 weeks spread across small PRs; near-zero regression risk if
done mechanically.

## 2. Two threading idioms coexist (P2)

**Finding.** Acknowledged in CLAUDE.md: legacy `Thread { } / runOnUiThread { }`
next to `lifecycleScope.launch + Dispatchers.IO`. `SshClient` itself spawns raw
threads (`startShellReader`, `execCommand` reader) and all its public methods
block.

**Proposal.**

1. Give `TerminalBackend` a coroutine-friendly facade: `suspend fun connect()`,
   `suspend fun exec()`, and expose shell output as a `Flow<String>`
   (`callbackFlow` around the reader). Callers stop hand-rolling threads.
2. Convert call sites opportunistically when a file is touched for other
   reasons; do not do a big-bang rewrite.
3. New rule for CLAUDE.md once the facade exists: "new code uses the suspend
   facade, never raw `Thread`".

**Effort.** Facade ~2–3 days; call-site conversion amortized.

## 3. Busy-wait and blocking details in `SshClient` (P2)

**Finding.**

- `execCommand` polls `ch.isClosed` in a 20 ms `Thread.sleep` loop
  (SshClient.kt:404–407) — acceptable, but the 1 s cap means slow servers
  yield `exitStatus = -1` even on success, and callers treat that as failure
  (`exitStatus == 0`).
- `sendCommand`/`sendText` do socket writes on the caller's thread — safe only
  because callers happen to be off-main; nothing enforces it (a `StrictMode`
  violation waiting to happen).

**Proposal.** Fold both into the coroutine facade (item 2): writes hop to
`Dispatchers.IO`; exec waits for channel close with a proper deadline derived
from the caller's `timeoutMs` rather than a fixed 1 s.

## 4. Serialization & storage choices (P2/P3)

- **Moshi reflection adapter.** `KotlinJsonAdapterFactory` (SshSettings.kt:10)
  pulls in `kotlin-reflect` (~2 MB dex, slower first parse) and breaks under
  R8 unless kept broadly. Moshi codegen (`moshi-kotlin-codegen` + `@JsonClass`)
  is a drop-in replacement given the models are simple data classes. (P2)
- **Hosts as one JSON blob** in `SecurePrefs` is fine at current scale; if
  host count/features grow (search, sort, per-host known keys), move hosts to
  SQLite alongside phrases/plays and keep only secrets in `SecurePrefs`. (P3)
- **Swallowed parse errors.** `getHosts()` returns `emptyList()` on any parse
  exception — a corrupt blob silently "deletes" all hosts. At minimum log and
  keep the raw JSON under a `hosts_json_backup` key so recovery is possible. (P2)

## 5. Dependency & build hygiene (P2)

- `google-api-services-drive:v3-rev20230815-2.0.0` is a 2023 revision; Drive
  uploads work but the pinned rev should be refreshed when touched.
- The `minifiedDebug` + testProguard setup is genuinely good — keep it.
- Consider enabling **configuration cache** and `org.gradle.caching=true` in
  `gradle.properties` for CI speed (verify AGP 9.2 compatibility first).
- `abiFilters` includes `x86_64` in release builds; if that exists only for
  emulator testing, move it to a debug-only flavor and shave APK size.

## 6. Error-handling convention drift (P3)

`runCatching { }.getOrElse { }` at boundaries is the stated convention and is
mostly followed, but several `catch (e: Exception)` blocks drop the exception
entirely (e.g. `SshSettings.getHosts`, several `runCatching { … }` with no
`onFailure`). Adopt a tiny `AppLog` helper so failures are at least visible in
logcat with a consistent tag, and make "never swallow silently" a review rule.
