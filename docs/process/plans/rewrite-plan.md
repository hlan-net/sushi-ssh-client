# Sushi rewrite plan — v0.9 → v1.0

*Status: reference · Written 2026-09-21 against `main` at `bb4ea00` (v0.8.3)*

> **Scope as decided on 2026-09-21:** v0.9.0 implements two pieces of this
> plan — the `TerminalBuffer` seam from §3.3 (one terminal model behind both
> `TerminalView` instances) and the conversation screen from §5.8, taken
> *first* rather than in the §5 order. The rest of the plan is kept as the
> reference for what a full rewrite would be and how it would be sequenced;
> it is not a commitment. `ROADMAP.md` §v0.9.0 is the authoritative list of
> what is being done.

This document is the specification for rewriting Sushi in full. It is written
to be executed by an AI coding agent working in this repository, one pull
request at a time, with a human maintainer reviewing and merging. Everything
the agent needs to decide is decided here; everything it must not do is
listed here; everything that must survive the rewrite is enumerated here.

Read the whole document before starting any phase. Then read `CLAUDE.md`.
Where the two disagree, this document wins until Phase 0 brings `CLAUDE.md`
in line with it.

---

## 0. Rules of engagement for the executing agent

These are not suggestions.

1. **One phase per pull request.** Within Phase 5, one screen group per PR.
   Never mix phases, never mix a phase with an unrelated fix.
2. **Every PR leaves the app releasable.** `main` must build, pass
   `./gradlew testDebugUnitTest lintDebug assembleDebug
   assembleMinifiedDebugAndroidTest`, and pass the emulator job in CI, after
   every merge. There is no "temporarily broken" state.
3. **Never delete legacy code before its replacement is wired in and tested**
   — in the same PR, or in one already merged. Old and new may coexist; a
   deleted feature may not.
4. **Never rename or restructure persisted data without a migration and a test
   that seeds the legacy format.** The data contract in §4 is binding. A PR
   that touches a file, key, table or column named there must include the
   migration and the seeded test.
5. **No new dependency without a row in §2** with a rationale, added in the
   same PR. No dependency outside the ones §2 approves.
6. **The target-side protocol is frozen in meaning, open to addition.**
   `~/.config/sushi/SUSHI.md`, `~/.config/sushi/config.conf`, `~/.sushi_logs/`,
   the persona init script and the shape of what `ConversationManager` sends
   to the model are part of what the user has on their servers. Nothing that
   exists changes meaning or format in this rewrite. A new *optional* file
   whose absence means "feature off" (such as `status.sh`, `ROADMAP.md`
   v0.9.x) is allowed, because a host initialised before it keeps behaving
   as before.
7. **Preserve behaviour, not code.** The existing tests (§6) are the
   behavioural specification. When you port one, its assertions move
   unchanged. When you cannot port one without changing what it asserts,
   stop and ask (rule 10).
8. **Verify before you push.** Run the commands in rule 2 locally. For a CI
   fix, reproduce the failure first. Read your own diff adversarially before
   pushing.
9. **Follow the repository's conventions**: Kotlin official style, 4-space
   indent, no wildcard imports, `val` by default, one top-level class per
   file, all user-visible strings in `res/values/strings.xml` and the four
   locales (`de`, `es`, `fi`, `sv`), conventional commit messages, the PR
   template in `.github/pull_request_template.md`. A layout or screen change
   needs a Figma link or an explicit `N/A` with a reason — the UX Gate job
   checks for it.
10. **Stop and ask the maintainer** when: a migration cannot be made
    lossless; a JSch limitation blocks a behaviour the old app had; an old
    test cannot be ported without changing its assertions; a phase's
    acceptance criteria cannot be met without widening its scope. Ask in the
    PR, with the specific question and your proposed answer. Do not guess.

---

## 1. Why a rewrite, and what kind

The app is feature-complete against its own roadmap (`ROADMAP.md`: v0.6,
v0.7, v0.8 all shipped), ships often (38 tags), and has 301 tests. It is also
built on a pattern that has run out of room:

- `MainActivity` is 1,686 lines and `SettingsActivity` 1,045. Every feature
  since v0.5 has landed in one of them because the stated architecture
  ("UI logic stays in activities") gave it nowhere else to go.
- Logic in those two files can only be verified on a device. v0.8.2 and
  v0.8.3 both consist entirely of bugs that device testing found after
  release, because nothing before a device could see them.
- `TerminalView` is a `TextView` with regex post-processing. It strips every
  escape it does not render, so `vim`, `htop`, `less` and `tmux` do not work
  (`docs/improvements/02-terminal-emulation.md`). The PTY is requested as
  `xterm`, so remote programs believe they can address the cursor. This is the
  single largest functional gap in an SSH client, and it cannot be fixed
  inside the current view.
- Threading is two idioms side by side, `SshClient`'s public API blocks, and
  `TerminalSessionHolder` is a process-wide singleton the UI reads directly.

The rewrite replaces every file in `app/src/main/java` and every layout. It
does so **bottom-up and in sequence**, so that each layer is replaced under
test before the layer above it, and the app ships between phases. It is a full
rewrite in what it produces, not in how it is executed: there is no
long-lived branch and no big-bang cutover.

What it deliberately keeps is listed in §2 and §3.4. The short version: the
SSH transport (JSch), the native PTY, the persisted data, the target-side
protocol, the CI pipeline and the tests' assertions.

---

## 2. Library and tooling decisions

Each row is a decision. "Keep" means the dependency survives to v1.0.
"Replace" names the replacement. "Remove" means it goes at cutover (Phase 6).

### 2.1 Build

| Today | Decision | Why |
|---|---|---|
| AGP 9.4.0, Gradle 9.7.1, JDK 17, AGP's built-in Kotlin | Keep. Add `gradle/libs.versions.toml`. | Multi-module needs one place for versions. |
| Single `:app` module | Replace with the module graph in §3.1. | Pure-JVM modules are what makes the core unit-testable. |
| `minifiedDebug` build type + `testBuildType = "minifiedDebug"` + the three ProGuard files | Keep the mechanism. Rewrite the rules as legacy classes go. | Running instrumented tests against the minified APK has caught real R8 breakage (`kotlin.collections.MapsKt` stripped, adapter `getCurrentList` stripped). It stays. |
| `lint-baseline.xml` (182 suppressed issues) | Delete at cutover. Each new module starts with no baseline. | The baseline is a debt ledger; the rewrite pays it. |
| NDK + CMake + `abiFilters arm64-v8a, armeabi-v7a, x86_64` | Keep the mechanism; **versions follow `docs/process/DEPENDENCY_LIFECYCLE.md`** — the newest LTS NDK line and current CMake, moved by the `ROADMAP.md` v0.9.x dependency PR before Phase 0, so this plan never pins r27 / 3.22.1. | `sushi-pty.c` needs a toolchain, not a particular one. |

### 2.2 Runtime dependencies

| Today | Decision | Why |
|---|---|---|
| `com.github.mwiede:jsch` 2.28.7 | **Keep**, wrapped in `:core:ssh`. | The only maintained Android-compatible SSH library with OpenSSH 9+/10+ kex and host-key algorithms. Nothing to gain by replacing it. JSch classes stay `-keep`'d (reflection-loaded crypto). |
| `com.jcraft:jzlib` 1.1.3 | **Remove** (scheduled in `ROADMAP.md` v0.9.x, before Phase 0). | The JSch jar ships `com.jcraft.jsch.juz.Compression` on `java.util.zip`; the app never enables compression. A 2013 dependency for nothing. |
| `bcprov-jdk18on` 1.86 | **Keep**. | Ed25519 on API < 33; ML-KEM. |
| `sushi-pty.c` (217 lines C, JNI) | **Keep as is.** Moves to `:app` unchanged. | Works, tested (`LocalShellBackendTest`), nothing to improve. |
| `androidx.security:security-crypto` 1.1.0 (`EncryptedSharedPreferences`) | **Replace** with an in-house `SecureStore`: Android Keystore AES-256-GCM key, values encrypted per entry, stored in a plain file or DataStore. Keep the library **only** to read the legacy file during migration (Phase 4); remove it two releases after cutover. | Google deprecated Jetpack Security Crypto; it will not get fixes. The migration must read the old file, so the library cannot go until the migration window closes. |
| `play-services-auth` 22.0.0, `androidx.credentials` 1.3.0, `credentials-play-services-auth`, `googleid` 1.2.0 | **Keep**. | This is the current Google sign-in path and was migrated to in v0.7.11. Nothing better exists. |
| `google-api-client-android`, `google-api-client-gson`, `google-http-client-android`, `google-api-services-drive` (2023 rev), plus the `guava` and `concurrent-futures` version pins that exist only to make them resolve | **Replace** with direct calls to the Drive REST API (`POST /upload/drive/v3/files?uploadType=multipart`) over OkHttp, authorised with the token Credential Manager / `AuthorizationClient` already yields. | Four dependencies and two classpath pins for one endpoint. `DriveLogUploader` is 112 lines; the REST version will be about the same. |
| `moshi-kotlin` 1.15.2 (reflection adapter) | **Replace** with `kotlinx-serialization-json`. | Reflection adapter pulls in `kotlin-reflect` and is R8-fragile (noted in `docs/improvements/04`). Field names in `SshConnectionConfig` are preserved exactly, so the stored hosts JSON stays readable — see §4.1. |
| `HttpURLConnection` hand-rolled in `GeminiClient`, `GitHubAuthManager`, `GitHubIssueClient`, `DriveAuthManager` | **Replace** with OkHttp behind one `HttpClient` interface in `:core:model`. | One client, one timeout policy, one place to test; streaming responses become possible for Gemini later. |
| `mlkit:genai-prompt` (Gemini Nano) | **Keep**. Stays `-keep`'d. | Only on-device path. |
| `kotlinx-coroutines-android` 1.11 | **Keep**. Add `kotlinx-coroutines-test`. | |
| `SQLiteOpenHelper` × 4 | **Replace** with Room, two databases (§4.2). | Migrations, `Flow` queries, compile-time SQL checking, DAOs. |
| `SharedPreferences` (`app_theme`) | **Replace** with DataStore Preferences. | |
| `appcompat`, `material` (Views), `viewpager2`, `constraintlayout`, `recyclerview`, view binding | **Remove at cutover.** | Replaced by Compose. `appcompat` stays until the last `AppCompatActivity` is gone. |
| `android.speech` (voice input in `MainActivity`) | **Keep** the platform API; rewrap in a `VoiceInput` class in `:app`. | |

### 2.3 New dependencies (approved)

| Dependency | For |
|---|---|
| Compose BOM (current stable), `compose-ui`, `compose-material3`, `compose-foundation`, `compose-ui-tooling-preview`, `activity-compose`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | UI. |
| `navigation-compose` with type-safe routes (`kotlinx-serialization` route classes) | Single-activity navigation. |
| `kotlinx-serialization-json` + the `org.jetbrains.kotlin.plugin.serialization` Gradle plugin | JSON, routes. |
| `okhttp` (current 4.x or 5.x stable) | HTTP. |
| `room-runtime`, `room-ktx`, `room-compiler` via KSP | Persistence. |
| `datastore-preferences` | Settings. |
| `compose-ui-test-junit4`, `compose-ui-test-manifest` | UI tests. |
| `kotlinx-coroutines-test`, `app.cash.turbine:turbine` | Flow/coroutine tests. |
| `androidx.test:runner`, `androidx.test:rules`, `androidx.test.ext:junit` (already present) | Instrumented tests. |

Resolve exact versions from Maven Central / Google Maven at the time of the
PR; pin them in `libs.versions.toml`; do not copy version numbers from this
document. Room's KSP processor is the one annotation processor in the build —
if AGP 9.4's built-in Kotlin and the KSP plugin disagree on Kotlin version,
that is a Phase 0 problem to solve, not to work around.

### 2.4 Decisions that are *not* dependencies

| Question | Decision | Why |
|---|---|---|
| Dependency injection | **Manual.** Constructor injection everywhere; one composition root, `AppGraph`, built in `SushiApplication`; ViewModels get their dependencies through a `ViewModelProvider.Factory` that reads the graph. No Hilt, no Koin. | The app has about fifteen injectable things. A DI framework would add an annotation processor (or a runtime resolver) for no gain, and Hilt + AGP 9 built-in Kotlin + KSP is a known source of build friction that an agent should not have to debug. |
| Architecture | **UDF.** `ViewModel` owns a `StateFlow<UiState>` and exposes intent functions; the Compose screen is a pure function of state; repositories expose `Flow`; use cases only where two repositories need coordinating. | Standard, and what makes the logic JVM-testable. |
| Activities | **One**, `MainActivity`, hosting `NavHost`. `ShareActivity` survives as a thin trampoline for `ACTION_SEND` that forwards into the nav graph. Every other activity is deleted in Phase 5. | Seventeen activities with intents between them is the old shape. |
| Terminal emulator | **Written in-house**, in `:core:terminal`, pure Kotlin. Spec in §3.3. | Termux's emulator is GPLv3 and this repository is Apache-2.0, so it cannot be bundled. jackpal's Android-Terminal-Emulator is Apache-2.0 but unmaintained since 2015. An emulator that is ours, JVM-testable and specified here is the better long-term asset, and it is the one piece of the rewrite that is a genuine engineering task rather than a port. |
| Terminal rendering | Compose `Canvas` drawing cell runs per row; no `TextView`. | A grid of cells needs a grid renderer. |
| Session ownership | A `SessionManager` in `:app`, application-scoped, holding `Map<HostId, TerminalSession>`, exposed as `StateFlow`. Replaces `TerminalSessionHolder`. Supports more than one live session from day one, even if the UI shows one. | The singleton with a typed `SshClient` escape hatch is the shape that made everything depend on everything. |
| Threading | Coroutines only. `Dispatchers.IO` for JSch and file work, structured concurrency under `viewModelScope` / the session's own scope. No `Thread {}`, no `runOnUiThread`. | |
| Logging | A `Logger` interface in `:core:model`; `android.util.Log` implementation in `:app`; a recording fake in tests. | `ConversationManager` imports `android.util.Log` and nothing else from Android. Remove that and it is pure JVM. |

---

## 3. Target architecture

### 3.1 Modules

```
:app             com.android.application — Compose UI, ViewModels, navigation,
                 AppGraph, SushiApplication, SshConnectionService, LocalShellBackend
                 + sushi-pty.c, ShareActivity trampoline, platform adapters
                 (Logger, VoiceInput, Keystore-backed SecureStore), migrations.

:data            com.android.library — Room databases + DAOs, DataStore settings,
                 repositories (HostRepository, PhraseRepository, PlayRepository,
                 CommandHistoryRepository, TranscriptRepository, SettingsRepository),
                 the SecureStore *interface* implementation glue, legacy migrator.

:core:ssh        JVM — SshSession (coroutine facade over JSch), host-key
                 verification (TOFU + known-hosts), key parsing/generation,
                 jump-host resolution, auth planning, SFTP.

:core:terminal   JVM — VT emulator: screen buffer, parser, SGR, key encoder.

:core:ai         JVM — CommandSafety, ConversationManager, ConversationContextBuilder,
                 ExecuteDirective, PersonaValidator, PersonaClient logic, the
                 ConversationLlm interface, GeminiClient (cloud, via HttpClient).
                 GeminiNanoClient stays in :app (needs Context + ML Kit).

:core:model      JVM — data classes (Host, Play, PlayParameter, Phrase,
                 CommandHistoryRecord, TranscriptEntry, ...), serialization,
                 HttpClient and Logger interfaces, SushiConfig, PlayRunner.
```

Dependency direction: `:app → :data → :core:*`, `:app → :core:*`,
`:core:{ssh,terminal,ai} → :core:model`. Nothing points upward. `:core:*`
modules contain **no `android.*` or `androidx.*` import**; CI enforces it
with a grep step added in Phase 0.

Module plugin: try `org.jetbrains.kotlin.jvm` for `:core:*` with the Kotlin
version AGP 9.4 bundles. If the plugin and AGP's built-in Kotlin cannot be
made to agree in Phase 0, fall back to `com.android.library` with no Android
dependencies and keep the grep guard — the purity is what matters, not the
plugin ID.

### 3.2 `:core:ssh` — the session facade

```kotlin
interface SshSession {
    val state: StateFlow<SessionState>          // Disconnected, Connecting, Connected(info), Failed(ConnectFailure)
    val output: Flow<ByteArray>                 // raw PTY bytes; the emulator decodes UTF-8
    suspend fun connect(prompts: SessionPrompts): Result<Unit>
    suspend fun send(bytes: ByteArray)
    suspend fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int)
    suspend fun exec(command: String, timeout: Duration, onChunk: ((String) -> Unit)? = null): ExecResult
    suspend fun sftpDownload(remotePath: String, sink: OutputStream): Result<Unit>
    suspend fun sftpUpload(source: InputStream, remotePath: String): Result<Unit>
    suspend fun disconnect()
}

interface SessionPrompts {                       // replaces DialogUserInfo / KeyPassphraseDialog coupling
    suspend fun hostKey(decision: HostKeyDecision): Boolean
    suspend fun password(prompt: String): String?
    suspend fun keyPassphrase(keyName: String): String?
    suspend fun keyboardInteractive(name: String, instruction: String, prompts: List<Prompt>): List<String>?
}
```

`LocalShellBackend` implements the same interface in `:app` (it needs JNI).
`ExecResult` carries `exitStatus: Int?` — `null` when the channel closed
without reporting one, never `-1` (see `docs/improvements/04` §3). The exec
deadline is the caller's `timeout`, not a fixed 1 s.

Everything `SshClient.kt` does today — password/key/keyboard-interactive
auth, `PreferredAuthentications` per host, jump host through a forwarded
port, `serverAliveInterval`, `StrictHostKeyChecking=ask` with
`TrackingHostKeyRepository`, PTY `xterm`, the exec channel — is preserved.
`JumpServerAuthPlanTest` (279 lines) is the specification for the auth
planning and moves here unchanged.

### 3.3 `:core:terminal` — the emulator

The centrepiece. Pure Kotlin, no Android, no Compose. Written against this
spec, tested against §6.3.

**Model**

- `Screen(cols, rows)`: primary and alternate buffers of `Cell(grapheme:
  String, fg: Color, bg: Color, attrs: Attrs)`. `grapheme` is one extended
  grapheme cluster — a base code point followed by its combining marks
  (`e` + U+0301, a flag pair, an emoji with a skin-tone modifier) — never a
  bare code point: a cell that stored one code point would drop every
  combining mark, and `é` typed as two code points would render as `e`.
  The empty cell is `""`, and the second half of a wide character is a
  sentinel `Cell.WIDE_TAIL`. `Color` is one of `Default`,
  `Indexed(0..255)`, `Rgb(r,g,b)` and `Attrs` is a bitset of bold, dim,
  italic, underline, blink, inverse, invisible, strikethrough.
- Scrollback: ring buffer of rows above the primary screen, size configurable
  (default 2,000; the old `MAX_LINES = 500` was too small — `docs/improvements/02` §4).
  The alternate screen has no scrollback.
- Cursor with position, visibility, and pending-wrap state.
- Scroll region (DECSTBM), tab stops (every 8, settable with HTS / TBC).
- Modes: DECAWM (auto-wrap, default on), DECTCEM (cursor visible), DECCKM
  (application cursor keys), DECKPAM/DECKPNM, DECOM (origin), IRM (insert),
  bracketed paste (2004), alternate screen (47, 1047, 1049 — with cursor
  save/restore for 1049), 1000/1002/1006 mouse tracking recorded but not
  acted on (no mouse in v1).
- Wide characters (East Asian Wide/Fullwidth) occupy two cells; a combining
  mark (general category `Mn`, `Mc`, `Me`, plus ZWJ sequences and variation
  selectors) is appended to the previous cell's `grapheme` and does not
  advance the cursor, also when it arrives in a later chunk. UTF-8 decoding
  is incremental across chunk boundaries.

**Parser** — the VT500 state machine (Paul Williams' description): `ground`,
`escape`, `escape_intermediate`, `csi_entry`, `csi_param`,
`csi_intermediate`, `csi_ignore`, `dcs_*` (consumed and ignored),
`osc_string`, `sos_pm_apc_string`. Unknown or malformed sequences are
consumed silently, never printed. An `ESC` in any state starts a new
sequence — the bug fixed in v0.8.3 (`TerminalView` swallowed output after a
truncated OSC) must be a test here.

**Sequences that must be implemented**

- C0: `BEL` (event), `BS`, `HT`, `LF`/`VT`/`FF` (line feed), `CR`, `SO`/`SI`
  (ignored — charset switching is a no-op, output is UTF-8).
- ESC: `7` / `8` (save/restore cursor), `D` (IND), `E` (NEL), `H` (HTS),
  `M` (RI), `c` (RIS), `=` / `>` (keypad modes), `( )` charset designators
  (consumed), `#8` (DECALN — fill with `E`, useful for tests).
- CSI: `CUU CUD CUF CUB CNL CPL CHA CUP HVP VPA` (cursor), `ED EL` (erase,
  all parameter values incl. 3 for scrollback), `IL DL ICH DCH ECH`
  (insert/delete), `SU SD` (scroll), `DECSTBM`, `SGR` (below), `DECSET` /
  `DECRST` (the modes above), `DSR 5` and `DSR 6` (respond), `DA` (respond
  as a VT220: `ESC [ ? 62 c` — a trailing `;` would announce another
  parameter; options, if ever advertised, go after it as `ESC [ ? 62 ; 22 c`),
  `TBC`, `REP`, `SCP`/`RCP` (`s`/`u`).
- SGR: `0`, `1`–`9`, `22`–`29`, `30`–`37`, `39`, `40`–`47`, `49`, `90`–`97`,
  `100`–`107`, `38;5;n`, `48;5;n`, `38;2;r;g;b`, `48;2;r;g;b`, and the
  colon-separated forms of the last four.
- OSC: `0`, `1`, `2` (title → event), `7` (cwd → event), `52` (clipboard,
  ignored). `BEL` or `ST` terminated.

**API**

```kotlin
class TerminalEmulator(cols: Int, rows: Int, scrollback: Int = 2000) {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun resize(cols: Int, rows: Int)
    val screen: Screen                          // read under the emulator's lock or via snapshot()
    fun snapshot(): ScreenSnapshot              // immutable copy for rendering
    val dirtyRows: Flow<IntRange>               // or a version counter — the renderer's choice
    val responses: Flow<ByteArray>              // DSR/DA replies; the session must write these back to the PTY
    val events: Flow<TerminalEvent>             // Bell, Title(String), Cwd(String), ModeChanged(...)
}

object KeyEncoder {
    fun encode(key: TerminalKey, modifiers: Modifiers, modes: Modes): ByteArray
    // arrows honour DECCKM (ESC [ A vs ESC O A), keypad honours DECKPAM,
    // Ctrl+letter = letter and 0x1F, Alt = ESC prefix, F1–F12, Home/End/PgUp/PgDn/Ins/Del.
}
```

**Performance requirement**: writing 1 MB of `ls --color -R` output in 64 KB
chunks must complete in under 200 ms on the JVM test runner, and the renderer
must repaint only dirty rows. A busy `tail -f` must not drop below 60 fps on
a mid-range phone. The old view was O(n²) per append (`docs/improvements/02`
§3); the emulator is O(chunk).

**Renderer** (`:app`, Phase 5): Compose `Canvas`; cell size measured once
from the glyph `W` at the current font size; each row drawn as runs of equal
style with `drawText`; cursor as a rectangle; selection by cell range, with
copy producing the plain text of the selected cells. Hardware keys through
`onPreviewKeyEvent`, soft keyboard through a hidden `BasicTextField` whose
committed text is sent as bytes, IME composing text shown as an overlay. The
extra-keys row (Esc, Tab, Ctrl, Alt, arrows, `|`, `/`, `-`, and the
existing Up/Down history buttons) is kept.

**Accessibility** — the one place the rewrite can make things worse. The
`TextView` gave TalkBack, selection and magnification for free; a `Canvas`
gives nothing. The renderer therefore exposes the screen to accessibility
services row by row (`Modifier.semantics` with a `contentDescription` per
visible row, `LiveRegion` on the cursor row so TalkBack announces new
output when enabled), keeps selection operable with Switch Access, gives
every key in the extra-keys row a 48 dp target, and passes
`TerminalContrastTest`'s thresholds for the default theme. These are
acceptance criteria of Phase 5.7, not follow-ups.

### 3.4 What is preserved exactly

- `sushi-pty.c` and its JNI signatures (`nativeStart`, `nativeRead`,
  `nativeWrite`, `nativeResize`, `nativeClose`).
- The `HostKind.LOCAL` seeded host (`SshSettings.seedLocalHostIfMissing`).
- `CommandSafety`'s three tiers and every pattern in it (`CommandSafetyTest`
  is the spec).
- `ConversationManager`'s troubleshooting loop: cap of 5 commands per
  message, stop on repeated command, CONFIRM pauses / BLOCKED ends
  (`ConversationManagerTroubleshootingTest` is the spec).
- `ConversationContextBuilder`'s prompt shape, including the Infrastructure
  section built from saved hosts and the injected recent command history.
- `PlayRunner`'s `{{ PARAM }}` substitution and its rule that a Play with a
  `secret` parameter is never recorded in command history
  (`PlayRunnerSecretsTest`).
- `SshConnectionService` as a `connectedDevice` foreground service that
  starts on connect and stops on disconnect.
- Backup exclusions: command history and transcripts never enter a backup or
  device transfer (§4.2 keeps them in their own database file for exactly
  this reason).
- The GitHub Device Flow feedback path (`GitHubAuthManager`,
  `GitHubIssueClient`, `GitHubDeviceFlowState`).
- All 516 string resources and their four translations. Strings may be
  renamed; none may be dropped without its screen being dropped.
- The theme: `AppThemeSettings` (mode, accent variant, terminal font size)
  and the `sushi_*` colour tokens, which were re-tuned for contrast in v0.8.3
  and are asserted by `TerminalContrastTest`. The Compose theme maps the same
  tokens, and Figma carries them as variables — collection *Sushi*, modes
  Light and Dark, `codeSyntax.ANDROID` set to the `R.color` name — created
  in Phase 0 so that a card's `get_design_context` yields token names rather
  than hex values. `colorPrimary` is whichever of the four accent variants
  (`sushi_green`, `sushi_accent_wasabi`, `sushi_accent_gari`,
  `sushi_accent_terracotta`) the user chose in Settings; the token is
  `color/primary`, the variants are primitives behind it; the ANSI palettes and the `readableOn` contrast nudge move into
  the emulator's colour resolution, with the same test.

### 3.5 Navigation map

One activity, one `NavHost`, type-safe routes. The map is a Figma card on
the Proposals page ([*Navigation map — rewrite*](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=114-2)), *Approved*
before Phase 5.1, because the first screen fixes the graph's shape. What it
must settle:

**Routes** (route class → what it shows):

| Route | Screen | Arguments |
|---|---|---|
| `Home` | Terminal tab + Plays tab, host switcher, setup checklist | — (start destination) |
| `Terminal(hostId)` | full-screen terminal for one session | host |
| `Conversation(hostId, sessionId?)` | the AI conversation | host; optional past session to reopen |
| `Hosts`, `HostEdit(hostId?)` | host list, host editor | optional host |
| `Keys`, `HostKeys` | key pair, trusted server keys | — |
| `Phrases`, `Plays`, `PlayEdit(playId?)` | lists and editor | optional play |
| `PlayRun(playId, hostId)` | run dialog with parameter preview | play, host |
| `CommandHistory(hostId)`, `GeminiHistory`, `Transcript(sessionId)` | history screens | — |
| `Persona(hostId)` | remote `SUSHI.md` editor | host |
| `Settings(page?)` | the four pages | optional page to open on |
| `Upload(uri)`, `Download(hostId)` | SFTP | from `ACTION_SEND` / from the terminal |
| `About` | — | — |

**Back stack** — `Home` is the root and the only screen with tabs.
`Terminal` and `Conversation` are siblings under `Home`: back from either
returns to `Home`, never to the other; switching between them is a
`navigate` with `popUpTo(Home)`. Host-key trust, host-key changed,
passphrase and delete confirmations are dialog destinations, not screens.
Nothing declares `configChanges`: rotation recreates the activity and every
screen survives through its `ViewModel` and `SavedStateHandle`.

**Deep links** — the entry points the small features in `ROADMAP.md`
v0.9.x need, and the ones the old activities served by intent:

| Entry | Route |
|---|---|
| Quick Settings tile, App Shortcut | `Home` with `connect=hostId` → connects, then `Terminal(hostId)` |
| `SshConnectionService` notification | `Terminal(hostId)` of the live session |
| `ACTION_SEND` via the `ShareActivity` trampoline | `Upload(uri)` with the host picker inside |
| `sushi://host/{id}` (future) | `Home` with `connect=hostId` |

**Session ownership** — `SessionManager` is application-scoped; no route
owns a session. Leaving `Terminal` does not disconnect; `Home`'s session
card shows the live session and offers *Return to terminal*, as today.

---

## 4. Data compatibility contract

A user updating from v0.8.3 to any release of the rewrite must lose nothing:
hosts, keys, passphrase, phrases, plays, command history, transcripts,
settings, GitHub feedback token. This section is the complete list of what
the old app stores. The migration in Phase 4 reads all of it.

### 4.1 `EncryptedSharedPreferences` file `sushi_secure_prefs`

Created with `MasterKeys.AES256_GCM_SPEC`, key scheme `AES256_SIV`, value
scheme `AES256_GCM`. Keys and types:

| Key | Type | Owner today |
|---|---|---|
| `ssh_private_key` | String (PEM) | `SshSettings` |
| `ssh_public_key` | String | `SshSettings` |
| `ssh_key_passphrase` | String | `SshSettings` |
| `ssh_hosts_json` | String — JSON array of `SshConnectionConfig` | `SshSettings` |
| `ssh_active_host_id` | String (UUID) | `SshSettings` |
| `ssh_hosts_json_backup` | String — last-known-good copy of `ssh_hosts_json`, written before every save *(added by `ROADMAP.md` v0.9.x; may be absent)* | `SshSettings` |
| `gemini_enabled` | Boolean | `GeminiSettings` |
| `gemini_api_key` | String | `GeminiSettings` |
| `gemini_cloud_model` | String | `GeminiSettings` |
| `gemini_nano_preferred` | Boolean | `GeminiSettings` |
| `gemini_auto_troubleshoot` | Boolean | `GeminiSettings` |
| `drive_logs_always_save` | Boolean | `DriveLogSettings` |
| `drive_account_email`, `drive_account_display_name` | String | `DriveAuthManager` — the signed-in identity; without them the user appears signed out after update |
| `ssh_host`, `ssh_port`, `ssh_username`, `ssh_password` | String / Int / String / String — **legacy single-host keys** from before hosts became a list | `SshSettings.seedLocalHostIfMissing` still converts them into `ssh_hosts_json` when that key is absent (`SshSettings.kt:144–163`); the migrator runs the same conversion first, then removes them |
| `feedback_github_token`, `feedback_github_username`, `feedback_github_device_code`, `feedback_github_user_code`, `feedback_github_verification_uri`, `feedback_github_expires_at_ms`, `feedback_github_interval_seconds` | String / Long | `FeedbackSettings` |

`SshConnectionConfig` JSON field names, which the new `Host` model must
serialise identically (kotlinx.serialization `@SerialName` where the Kotlin
name differs):

`kind` (`"SSH"` | `"LOCAL"`), `id`, `alias`, `host`, `port`, `username`,
`password`, `authPreference` (`"auto"` | `"password"` | `"key"`, nullable),
`privateKey` (nullable), `jumpEnabled`, `jumpHostId` (nullable), `jumpHost`,
`jumpPort`, `jumpUsername`, `jumpPassword`, `jumpAuthPreference` (nullable),
`startupCommand` (nullable, *added by `ROADMAP.md` v0.9.x; absent in older blobs*).
Missing fields take the defaults in `SshClient.kt:34-50`. Unknown fields are
ignored (`ignoreUnknownKeys = true`).

### 4.2 SQLite databases

| File | Version | Table(s) | New home |
|---|---|---|---|
| `sushi_phrases.db` | 1 | phrases | Room `sushi.db` — **backed up** |
| `sushi_plays.db` | 2 | plays (`parametersJson` column holds a JSON array of `PlayParameter`) | Room `sushi.db` — **backed up** |
| `sushi_command_history.db` | 1 | command history | Room `sushi_private.db` — **excluded from backup** |
| `sushi_gemini_transcripts.db` | 1 | sessions + entries | Room `sushi_private.db` — **excluded from backup** |

Two Room databases, not one, because `backup_rules.xml` and
`data_extraction_rules.xml` exclude history and transcripts by file name
(they contain command output that can carry secrets). Merging everything
into one file would either back up secrets or stop backing up phrases and
plays. The new rules exclude `sushi_private.db` and its `-journal`, `-wal`,
`-shm` siblings, exactly as they exclude the two old files today.

The exact `CREATE TABLE` statements are in the four `*DatabaseHelper.kt`
files. The migrator copies them into itself verbatim (as the legacy schema it
reads), because those files are deleted at cutover.

### 4.3 Plain `SharedPreferences` file `app_theme`

`theme_mode`, `accent_variant`, `terminal_font_size` → DataStore.

### 4.4 Migration procedure (Phase 4)

`LegacyMigrator` runs once, on first launch after update, before any
repository is read, and is idempotent:

1. If DataStore has `migration_version >= 1`, return.
2. Open each legacy source that exists. A missing source is not an error
   (fresh install).
3. Copy into the new stores **atomically and idempotently**. Each Room
   database is written in one transaction that begins by clearing its
   target tables — they hold nothing but migrated rows until step 4 — and
   ends with `count(new) == count(old)` per table, or rolls back. Secure
   store and DataStore entries are written by key, so a second pass
   overwrites rather than duplicates. A failure anywhere leaves the legacy
   files untouched and the new stores exactly as they were before the
   attempt, logs, and surfaces a one-time error.
4. Only after every source succeeded: set `migration_version = 1` and
   clear `migration_failed_at`. On failure set `migration_failed_at` to
   the attempt's timestamp instead. Legacy files are **not deleted in this
   release**; a later release (after Phase 6 + two versions) deletes them.
   Until then the old app could be reinstalled and still find its data.

The two DataStore keys give the data layer exactly three states, and every
facade and repository (Phase 4) reads them the same way:

| State | Keys | Reads | Writes |
|---|---|---|---|
| **Not started** — the migrator has not run (feature flag off, or first launch before it ran) | neither set | legacy stores | legacy stores |
| **Failed** — the migrator ran and rolled back | `migration_failed_at` set, `migration_version` unset | legacy stores | **refused** with a visible message; the next launch retries from a clean target |
| **Completed** | `migration_version = 1` | new stores | new stores |

*Not started* is transparent: the app is the old app with new class
names underneath. *Failed* degrades to read-only on the old data — never to
an empty app, duplicate rows, or a crash loop. *Completed* is the only
state in which the new stores are read. A migrated store is never written
in *Not started* or *Failed*, and a legacy store is never written in
*Completed*, which is what keeps the retry in step 3 safe.

Test: an instrumented test that writes fixture files in the legacy formats
(an `EncryptedSharedPreferences` with every key above, the four databases
with representative rows including a Play with a `secret` parameter and a
host with a jump host) into the test app's data directory, runs the
migrator, and asserts every value round-trips. This test is the acceptance
criterion of Phase 4 and stays in the suite permanently.

### 4.5 Target-side files — frozen

`~/.config/sushi/SUSHI.md`, `~/.config/sushi/config.conf` (`log_dir`),
`~/.sushi_logs/`, the persona init script. Not part of the app's storage,
but part of what users have. Unchanged.

---

## 5. Phases

Each phase is one PR unless stated. Every phase ends with rule 2's commands
green locally and CI green. Phases 2, 3 and 4 are independent of each other
and may be done in any order once Phase 1 has merged; Phase 5 needs all of
2, 3 and 4.

### Phase 0 — Foundation (no behaviour change)

**Deliverables**

- `gradle/libs.versions.toml` with every current dependency and every §2.3
  addition that Phase 0 itself needs (serialization plugin, KSP, coroutines-test).
- Empty modules `:core:model`, `:core:ssh`, `:core:terminal`, `:core:ai`,
  `:data`, each with one trivial test, wired into `settings.gradle.kts`;
  `:app` depends on all of them. Settle the JVM-plugin-vs-AGP question here
  (§3.1).
- CI: `android-ci.yml` runs `./gradlew test` (all modules) and a step that
  fails if `grep -rE '^import android' core/` matches anything.
- `CLAUDE.md`: replace the Architecture section with §3 of this document in
  summary form and a link here; replace "Adding features" accordingly; keep
  the build commands, machine setup and SSH-credentials sections.
- `ROADMAP.md`: **only if the maintainer has committed to the full rewrite**
  (see the scope note at the top), add a `v1.0.0 — Rewrite` section that
  links here and lists the phases with checkboxes, and mark this file *in
  progress*. Phase 0 never touches the v0.9.0 section, which is scoped on
  its own terms; until that commitment this file stays *reference* and
  Phase 0 is not started.
- **UX, before any Compose PR** — the repository's process
  (`docs/process/UX_PROPOSALS.md`) says every layout change starts from an
  *Approved* Figma card, and Phase 5 is nine layout changes:
  - `ux-gate.yml`'s file pattern extended so Compose screens trigger it
    (`/ui/.*\.kt$`, `Screen\.kt$`, `Theme\.kt$`, and `colors.xml` in
    `values` and `values-night`). Today it matches only layout XML and
    `*Activity.kt`; from Phase 5.1 on it would pass every UI change silently.
  - **Nine proposal cards** on the Proposals page, one per Phase 5 screen
    group, each with a *Current State* screenshot from the v0.8.3 build.
    Figma's *Main Screens* page still shows v0.5; these cards become the
    baseline it lacks. Cards start *In Design*; Phase 5.n does not start
    until its card is *Approved*.
  - The **navigation map** card (§3.5), *Approved* before Phase 5.1: the
    first screen fixes the shape of the graph. *Made 2026-09-21:*
    [card](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=114-2).
  - **Design tokens in Figma** (§3.4): the variable collection, text styles
    and five base components, so every card from here on is drawn with the
    tokens the Compose theme implements and `get_design_context` returns
    names, not hex. *Made 2026-09-21:* collections *Sushi* (39 colour
    tokens, Light/Dark, `codeSyntax.ANDROID` = the `R.color` name) and
    *Sushi Layout* (spacing, radius, touch target, stroke), six text styles
    `Sushi/Headline … Terminal`, and the [Components page](https://www.figma.com/design/heP71zbxhc6Mtgpghp0dDw/Sushi?node-id=117-2) with
    Button, Chip, Card, TextField and Banner bound to them. What remains for
    Phase 0 is the nine screen cards.

**Acceptance**: the app is byte-for-byte the same in behaviour; all 301
existing tests still pass; new modules build and their placeholder tests run
in CI.

### Phase 1 — Pure core: `:core:model` + `:core:ai`

**Move, do not rewrite.** These classes are already Android-free or nearly:

| From `:app` | To | Change |
|---|---|---|
| `CommandSafety`, `ExecuteDirective`, `PersonaValidator`, `SushiConfig`, `Play`, `PlayRunner`, `GitHubDeviceFlowState`, `SshKnownHosts`, `CommandSource`, `ConversationTurn`, `SetupChecklist` | `:core:model` / `:core:ai` | Package rename only. |
| `ConversationManager`, `ConversationContextBuilder`, `PersonaClient`, `ConversationLlm` | `:core:ai` | `android.util.Log` → injected `Logger`. |
| `GeminiClient` | `:core:ai` | `Context` parameter removed (it was only used to read settings — pass the values); `HttpURLConnection` → `HttpClient` interface with an OkHttp implementation in `:app` and a fake in tests. |
| `SshConnectionConfig`, `HostKind`, `SshAuthPreference` | `:core:model` as `Host`, `HostKind`, `AuthPreference` | Moshi → kotlinx.serialization with §4.1's field names; `SshSettings` in `:app` keeps writing the same JSON, now through the new serializer. A unit test round-trips a fixture JSON captured from the old adapter. |
| The 174 unit tests for the above | The new modules' `src/test` | Assertions unchanged. |

`:app` code that used these keeps working through imports of the new
packages. Nothing in `:app` is rewritten in this phase.

**Acceptance**: `./gradlew :core:model:test :core:ai:test` runs at least
the tests that moved; `:app` unit test count drops by the same number; the
instrumented suite is untouched and green; the grep guard passes.

### Phase 2 — `:core:terminal`

Build the emulator to §3.3. Not wired into any UI yet.

**Tests** (the acceptance criteria, all JVM):

- One test class per sequence family (cursor, erase, insert/delete, scroll
  region, SGR, modes, OSC, C0) asserting on `snapshot()`.
- The `TerminalViewEscapeTest`, `TerminalViewCursorKeyTest` and
  `TerminalViewLogLineTest` cases ported: same inputs, assertions rewritten
  against the screen rather than a `TextView`'s text. Every one of the
  v0.8.3 escape cases (`ESC = ESC ( B` leaves nothing visible; `ESC ] ...
  ESC ESC [ 31 m` re-synchronises) must be present.
- `TerminalContrastTest`'s ANSI-pair assertions ported to the emulator's
  colour resolver (the palettes and `readableOn` move with them).
- Golden tests: a `src/test/resources/streams/` corpus of byte streams with
  expected screen dumps — at minimum a bash prompt with OSC title, `ls
  --color`, a `vim` session opening and quitting a file, `htop` for two
  frames, `less` paging, a progress bar using `\r`, and a UTF-8 stream with
  wide and combining characters. Expected dumps are produced by hand-checking
  the first run and committed; a change to a dump is a reviewable diff.
- The performance test in §3.3.

**Acceptance**: every test above green; `:core:terminal` has no dependency
but `kotlin-stdlib` and coroutines.

### Phase 3 — `:core:ssh`

Build `SshSession` to §3.2 over JSch. `LocalShellBackend` is adapted to the
same interface in `:app` (it stays there because of JNI).

**Tests**

- `JumpServerAuthPlanTest`, `SshKnownHostsTest`,
  `TrackingHostKeyRepositoryTest`, `ConnectionFailureClassificationTest`
  moved with assertions unchanged.
- `JschRuntimeTest` (instrumented: proves the crypto providers survive R8)
  stays instrumented in `:app`, pointed at the new facade.
- `LocalSshIntegrationTest` (1,385 lines, needs `.local/local-ssh-test.env`)
  is ported to the facade and kept as the opt-in end-to-end suite.
- New: a fake `SshSession` in `:core:ssh`'s `testFixtures` for the UI tests
  of Phase 5.

**Acceptance**: tests green; the old `SshClient` still exists and the old
UI still uses it — this phase adds the new session beside it. (Swapping the
UI over is Phase 5.)

### Phase 4 — `:data`

Room (two databases per §4.2), DataStore settings, `SecureStore`
(Keystore AES-256-GCM in `:app`, interface in `:data`), repositories
exposing `Flow`, and `LegacyMigrator` per §4.4.

**One store for both UI patterns.** Phase 5 replaces screens one at a
time, so for its whole duration a Compose screen on the new repositories
and a legacy activity on `GeminiSettings` / `SshSettings` / the
`*DatabaseHelper`s run side by side. If those read different stores, an
API key saved in the new Settings never reaches `MainActivity`, and a
command the old terminal records after migration never reaches the new
history screen — `migration_version` rightly forbids copying twice. So in
this phase every legacy accessor becomes a **facade over the new
repository**, keeping its class name and signatures: `GeminiSettings`,
`SshSettings`, `DriveLogSettings`, `FeedbackSettings`, `AppThemeSettings`,
`KeyPassphraseCache`, `PhraseDatabaseHelper`, `PlayDatabaseHelper`,
`CommandHistoryDatabaseHelper`, `GeminiTranscriptDatabaseHelper`,
`TerminalLogRepository`, `ConsoleLogRepository`. A facade routes by the
three states of §4.4: in *Not started* it reads **and writes** the legacy
store, exactly as the class it replaced did; in *Failed* it reads the
legacy store and refuses writes; in *Completed* every screen — old or new
— reads and writes the new store through it. The facades are deleted in
Phase 6 with their last caller.

**Tests**

- DAO tests (instrumented, in-memory Room).
- The migration test described in §4.4, with committed fixtures.
- `CommandHistoryDatabaseHelperTest` and
  `GeminiTranscriptDatabaseHelperTest` assertions ported to the
  repositories.
- **State routing**, JVM, per facade: in *Not started* a write lands in
  the legacy store and the new store stays empty; in *Failed* a write is
  refused and the legacy store is unchanged; in *Completed* a write lands
  in the new store and the legacy store is unchanged.
- **Coexistence**, instrumented, against a migrated store: a value written
  through `GeminiRepository` is read back through `GeminiSettings` and
  vice versa; a command recorded through `CommandHistoryDatabaseHelper`
  after migration is returned by `CommandHistoryRepository`; a host saved
  through the new `HostRepository` is what `SshSettings.getHosts()`
  returns. This test stays until Phase 6 removes the facades.

**Acceptance**: migration, state-routing and coexistence tests green;
with the feature flag off the data layer is in *Not started* and the app
behaves as before, reads and writes alike; the migrator is invoked from
`SushiApplication` behind that flag, which stays off until Phase 5's first
screen needs the new repositories — and turning it on is safe precisely
because the facades route by state.

### Phase 5 — `:app` UI, screen by screen (several PRs)

Single `MainActivity` with `NavHost`, `AppGraph`, `SessionManager`. Each PR
replaces one screen group: new `ViewModel` + Compose screen + Compose UI
test, navigation switched to it, **the legacy activity and its layouts
deleted in the same PR**.

**Precondition for every group: its Figma card is *Approved*** and Phase
4's facades are in place, so the screen being replaced and the screens
still legacy share one store from the first Compose PR on (Phase 0
creates them; `docs/process/UX_PROPOSALS.md` §4 says how to build from
one). **The default is a 1:1 port** — same elements, same order, same
texts, same states as the current screen — because a rewrite that also
redesigns nine screens is the scope creep rule 1 forbids. The card's
*Proposed Design* panel may show a deviation from today's screen, and
then the PR implements the card, not the old screen; but a deviation lives
on the card *before* the PR, never in the PR alone. Improvements from
`docs/improvements/06-ux.md` ride along only when the card shows them and
the PR does not grow. The nav map (§3.5) is the tenth card and is
*Approved* before 5.1.

Order, chosen so the riskiest screens go last and each PR can reuse what
the previous one built:

1. Settings (four pages) — turns the migrator on; first user of `:data`.
2. Hosts list, host editor, SSH keys, host keys (known hosts).
3. Phrases, Plays (list + editor + run dialog with parameter preview).
4. Command history, Gemini history + transcript detail.
5. Persona editor, About, feedback (Device Flow).
6. Share (`ACTION_SEND` → SFTP upload) and SFTP download.
7. **Main**: Terminal tab on the new emulator + renderer, Plays tab, host
   switcher, setup checklist, connection status, voice input.
8. Gemini conversation — **already a Compose screen** by then
   (`ROADMAP.md` v0.9.0 replaced the dialog first). This step re-homes
   `ConversationScreen` onto the nav graph (`Conversation(host, session?)`),
   `SessionManager` and `:core:ai`'s `ConversationManager`, and deletes the
   shim that mounted it inside `MainActivity`. If v0.9.0 has not shipped
   when this step is reached, it does both.
9. Full-screen terminal (`TerminalActivity`'s role) as a destination of
   the same nav graph, sharing the session with the Main tab through
   `SessionManager`.

Each screen's instrumented coverage from `DeviceQaSuiteTest`,
`TerminalKeyRowLayoutTest`, `TerminalViewSelectionTest`,
`LayoutInflationTest` and `AiConversationTest` is ported as Compose UI
tests in the PR that replaces the screen; the old test is deleted with the
old screen. `DeviceQaSuiteTest`'s wake/unlock scaffolding stays for the
device runner.

**Acceptance per PR**: the replaced screen's UI tests green on the emulator;
the legacy activity gone from the manifest; no `binding.*` references to the
deleted layouts; the app releasable; the card linked under *Figma frame*,
its UX checklist ticked (all six boxes, accessibility included), its status
moved to *Merged*; any deviation from the card written on the card and in
the PR. For 5.7 additionally the accessibility criteria in §3.3: TalkBack
reads the terminal row by row, selection works with Switch Access, every
key is a 48 dp target.

### Phase 6 — Cutover and removal

- Delete every remaining legacy class, layout, adapter, `TerminalView`,
  `TerminalSessionHolder`, `SshClient`, the four `SQLiteOpenHelper`s, `SecurePrefs`.
- Remove `appcompat`, `material` (Views), `viewpager2`, `constraintlayout`,
  `recyclerview`, view binding, Moshi, the Google API client stack, Guava
  and `concurrent-futures` pins.
- Rewrite `proguard-rules.pro`, `minified-debug-proguard-rules.pro`,
  `test-proguard-rules.pro` from scratch for what remains (JSch, ML Kit,
  JNI, Room, serialization). Delete `lint-baseline.xml`; fix or
  individually suppress what lint reports.
- Keep `security-crypto` for the migrator only; schedule its removal.
- `CHANGELOG.md` `[1.0.0]`, `README.md` highlights, `ROADMAP.md` checkboxes,
  this file marked *done*.

**Acceptance**: `app/src/main/java` contains no file that existed at
`bb4ea00` except `LocalShellBackend.kt`'s JNI declarations and
`sushi-pty.c`; lint baseline gone with lint clean; all CI jobs green;
release `v1.0.0` built from the tag.

### Phase 7 — Post-cutover (separate, later)

Delete legacy data files and `security-crypto` two releases after 1.0.0.
Then the backlog (`ROADMAP.md`): port forwarding and SOCKS are now three
methods on `SshSession`; multiple concurrent sessions are a UI change on
top of `SessionManager`; desktop-mode layouts are Compose `WindowSizeClass`.

---

## 6. Test strategy

### 6.1 What the numbers should look like

Today: 174 JVM tests, 127 instrumented. After Phase 6 the JVM count should
be several times larger (the emulator alone will carry hundreds of small
tests) and the instrumented count should be *smaller*, because everything
that was instrumented only for lack of a seam moves to the JVM. The
instrumented suite is for: Room DAOs, the migration, R8 survival
(`JschRuntimeTest`), Compose screens, the device QA suite, the PTY, and the
opt-in SSH integration test.

### 6.2 Where each existing test goes

| Test (today) | Kind | Goes to |
|---|---|---|
| `JumpServerAuthPlanTest`, `SshKnownHostsTest`, `TrackingHostKeyRepositoryTest`, `ConnectionFailureClassificationTest` | JVM | `:core:ssh` (Phase 3) |
| `CommandSafetyTest`, `ConversationManagerTroubleshootingTest`, `ConversationContextBuilderTest`, `ExecuteDirectiveTest`, `PersonaValidatorTest` | JVM | `:core:ai` (Phase 1) |
| `SushiConfigTest`, `PlayRunnerSecretsTest`, `GitHubDeviceFlowStateTest`, `SetupChecklistTest` | JVM | `:core:model` (Phase 1) |
| `TerminalViewEscapeTest`, `TerminalViewCursorKeyTest`, `TerminalViewLogLineTest`, `TerminalContrastTest` (ANSI pairs) | instrumented → **JVM** | `:core:terminal` (Phase 2) |
| `TerminalContrastTest` (surface/theme half) | instrumented | Compose theme test in `:app` (Phase 5.7) |
| `TerminalKeyRowLayoutTest`, `TerminalViewSelectionTest`, `LayoutInflationTest`, `AiConversationTest`, `DeviceQaSuiteTest` | instrumented | Compose UI tests, per screen (Phase 5) |
| `CommandHistoryDatabaseHelperTest`, `GeminiTranscriptDatabaseHelperTest` | instrumented | `:data` DAO/repository tests (Phase 4) |
| `LocalShellBackendTest` | instrumented | `:app`, unchanged (Phase 3) |
| `JschRuntimeTest` | instrumented | `:app`, pointed at the facade (Phase 3) |
| `LocalSshIntegrationTest` | instrumented, opt-in | `:app`, ported to the facade (Phase 3) |
| `ExampleUnitTest`, `ExampleInstrumentedTest` | — | deleted |

### 6.3 Golden streams for the emulator

Committed under `core/terminal/src/test/resources/streams/<name>.bin` with
`<name>.expected` (a plain-text screen dump plus a JSON header with cursor
position and mode flags). The first run of each writes the dump; the author
inspects it by eye against the real program's appearance and commits it.
From then on any change is a diff in review. A golden test never
regenerates its expectation automatically.

### 6.4 CI

`android-ci.yml` builds and runs all JVM tests; `emulator-tests.yml` runs
the instrumented suite on API 37; `device-tests.yml` runs the device QA
suite on the self-hosted runner; `ux-gate.yml` checks the Figma link. All
four stay. Phase 0 adds the purity grep. No phase may make any of them
slower than 1.5× its current time without saying so in the PR.

---

## 7. Risks and stop conditions

| Risk | Mitigation | Stop if |
|---|---|---|
| Emulator correctness — interactive programs still render wrong | Golden corpus grown from real programs; the Williams parser; `vttest` run manually against a debug build before Phase 5.7 merges | A golden test cannot be made to pass without a hack that special-cases the program |
| Migration loses data | Row-count assertions; legacy files retained; seeded test | The seeded test cannot be made to round-trip every value in §4.1 |
| KSP / built-in Kotlin / AGP 9.4 build friction (Room) | Resolve in Phase 0 with nothing else in the PR | Two working days without a green build — then ask whether to pin AGP/Kotlin differently |
| Keystore `SecureStore` fails on a device where the old library worked (StrongBox, key invalidation after biometric change) | Never bind the key to user authentication; catch `KeyPermanentlyInvalidatedException` and re-prompt for what cannot be recovered (the API key, the GitHub token) rather than crashing | — |
| Compose IME handling for the terminal (composing text, autocorrect, hardware keyboards) | Hidden `BasicTextField` pattern; `onPreviewKeyEvent`; test on a physical keyboard before Phase 5.7 merges | — |
| JSch on Android 16+ with newer server defaults | Already on the maintained fork; keep it current via Dependabot | A required kex/host-key algorithm is not in the fork |
| The rewrite stalls with half the screens migrated | Every PR leaves a releasable app; a stall is a working app with two UI patterns, which is what v0.8.3 already is | — |

---

## 8. Out of scope for the rewrite

Not because they are unwanted — because mixing them in would make the
rewrite unreviewable. They come after 1.0.0 and are cheaper then:

- Mouse reporting in the terminal.
- Sixel / Kitty graphics.
- Multiple *visible* sessions (tabs/split); `SessionManager` supports the
  data side from Phase 5, the UI comes later.
- Port forwarding, SOCKS, SCP from an in-app picker (backlog B-7, B-10,
  B-11, B-16).
- Any change to what the AI is asked or how `SUSHI.md` is generated.
- New locales.

---

## 9. Appendix — where every current file goes

| File | Lines | Destination |
|---|---|---|
| `MainActivity.kt` | 1686 | Rewritten: `:app` nav host + `MainScreen`, `TerminalTab`, `PlaysTab`, `HostSwitcher`, `SetupChecklistCard`, `VoiceInput`, their ViewModels (Phase 5.7) |
| `SettingsActivity.kt` | 1045 | Rewritten: four Compose pages + ViewModels (Phase 5.1) |
| `SshClient.kt` | 905 | Rewritten as `:core:ssh` `JschSession` (Phase 3) |
| `ConversationManager.kt` | 862 | Moved to `:core:ai`, `Log` → `Logger` (Phase 1) |
| `TerminalView.kt` | 605 | Replaced by `:core:terminal` + Compose renderer (Phases 2, 5.7) |
| `TerminalActivity.kt` | 463 | Rewritten as a nav destination (Phase 5.9) |
| `GeminiClient.kt` | 327 | Moved to `:core:ai` on `HttpClient` (Phase 1) |
| `CommandHistoryActivity.kt` | 322 | Rewritten (Phase 5.4) |
| `CommandHistoryDatabaseHelper.kt` | 311 | Replaced by Room DAO in `sushi_private.db` (Phase 4) |
| `PersonaEditorActivity.kt` | 292 | Rewritten (Phase 5.5) |
| `LocalShellBackend.kt` | 248 | Adapted to `SshSession` interface, stays in `:app` (Phase 3) |
| `PersonaClient.kt` | 247 | Moved to `:core:ai` (Phase 1) |
| `GeminiNanoClient.kt` | 243 | Stays in `:app` (ML Kit needs `Context`), behind `ConversationLlm` |
| `GeminiHistoryActivity.kt` | 239 | Rewritten (Phase 5.4) |
| `HostEditActivity.kt` | 233 | Rewritten (Phase 5.2) |
| `SftpDownloadActivity.kt` | 232 | Rewritten (Phase 5.6) |
| `ManagedPlays.kt` | 226 | Moved to `:core:model` (Phase 1) |
| `PhrasesActivity.kt` | 222 | Rewritten (Phase 5.3) |
| `GeminiTranscriptDatabaseHelper.kt` | 222 | Replaced by Room (Phase 4) |
| `PlayDatabaseHelper.kt` | 220 | Replaced by Room (Phase 4) |
| `CommandSafety.kt` | 220 | Moved to `:core:ai` (Phase 1) |
| `ShareActivity.kt` | 206 | Thin trampoline + Compose upload screen (Phase 5.6) |
| `DriveAuthManager.kt` | 200 | Rewritten on Credential Manager + OkHttp, `:app` (Phase 5.1) |
| `PlaysActivity.kt` | 180 | Rewritten (Phase 5.3) |
| `SshSettings.kt` | 178 | Replaced by `HostRepository` + `SecureStore` (Phase 4) |
| `PhraseDatabaseHelper.kt` | 172 | Replaced by Room (Phase 4) |
| `GitHubAuthManager.kt` | 161 | Rewritten on OkHttp, logic to `:core:model` (Phase 5.5) |
| `DialogUserInfo.kt` | 149 | Replaced by `SessionPrompts` (Phase 3) + Compose dialogs (Phase 5) |
| `KeysActivity.kt` | 135 | Rewritten (Phase 5.2) |
| `PlayRunner.kt` | 129 | Moved to `:core:model` (Phase 1) |
| `SshConnectionService.kt` | 126 | Kept, driven by `SessionManager` (Phase 5.7) |
| `ConversationContextBuilder.kt` | 120 | Moved to `:core:ai` (Phase 1) |
| `DriveLogUploader.kt` | 112 | Rewritten on Drive REST + OkHttp (Phase 5.1) |
| `AppThemeSettings.kt` | 97 | Replaced by DataStore `SettingsRepository` + Compose theme (Phase 4, 5.1) |
| `GitHubIssueClient.kt` | 93 | Rewritten on OkHttp (Phase 5.5) |
| `Play.kt`, `FeedbackSettings.kt`, `HostKeysActivity.kt`, `HostKeyDialogs.kt`, `HostAdapter.kt`, `SushiConfig.kt`, `HostsActivity.kt`, `TerminalSessionHolder.kt`, `PhrasePickerHelper.kt`, `HostKeyAdapter.kt`, `ExecuteDirective.kt`, `TrackingHostKeyRepository.kt`, `KeyPassphraseDialog.kt`, `GeminiSettings.kt`, `PhraseAdapter.kt`, `PlayAdapter.kt`, `GitHubDeviceFlowState.kt`, `TerminalBackend.kt`, `PersonaValidator.kt`, `SshKnownHosts.kt`, `GeminiTranscriptRecord.kt`, `GeminiTranscriptAdapter.kt`, `ConsoleLogRepository.kt`, `AppUtils.kt`, `AboutActivity.kt`, `CommandSource.kt`, `SetupChecklist.kt`, `CommandHistoryRecord.kt`, `TerminalLogRepository.kt`, `HostLabels.kt`, `SushiApplication.kt`, `SecurePrefs.kt`, `ConversationLlm.kt`, `KeyPassphraseCache.kt`, `DriveLogSettings.kt`, `ConversationTurn.kt`, `CommandHistoryHost.kt`, `ShellUtils.kt`, `Phrase.kt`, `GeminiTranscriptEntry.kt` | < 90 each | Models and pure logic → `:core:model` / `:core:ai` (Phase 1); adapters, dialogs and activities → deleted with their screens (Phase 5); settings classes → `:data` (Phase 4); `SushiApplication` → rewritten around `AppGraph` (Phase 5.1) |
| `cpp/sushi-pty.c` | 217 | Unchanged |
| 39 layouts, 2 menus, 28 drawables | — | Layouts and menus deleted with their screens; vector drawables kept as `ImageVector`s or `painterResource` |
