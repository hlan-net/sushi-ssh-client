# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Sushi is an Android SSH client (package `net.hlan.sushi`, min SDK 26, target SDK 36). Single Gradle module `:app`, Kotlin DSL, JDK 17 required.

## Build commands

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew lint                          # or lintDebug
./gradlew testDebugUnitTest             # JVM unit tests
./gradlew testDebugUnitTest --tests "net.hlan.sushi.ExampleUnitTest.testAddition_isCorrect"
./gradlew connectedDebugAndroidTest     # requires device/emulator
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.hlan.sushi.JschRuntimeTest
```

Lint reports go to `app/build/reports/`.

## Machine-specific setup

- `local.properties` is gitignored; set `sdk.dir=/absolute/path/to/sdk` on each machine.
- Required SDK packages: `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`, `ndk;27.0.12077973`, `cmake;3.22.1`.
- If native builds fail, validate NDK toolchain directly:
  `.../ndk/27.0.12077973/toolchains/llvm/prebuilt/.../bin/clang --version`
  and install missing host compatibility libraries.

### Local dev scripts

```bash
./scripts/install-wifi-debug.sh [device]   # build + install via Wi-Fi ADB (auto-bumps versionCode)
./scripts/run-device-qa-suite.sh           # runs instrumented QA suite on device
./scripts/setup-local-ssh-test.sh          # wizard to store SSH test credentials
./scripts/run-local-ssh-test.sh            # runs LocalSshIntegrationTest
./scripts/install-git-hooks.sh             # installs pre-push hook (runs unit tests)
./scripts/unlock-device.sh [device]        # enters the device PIN (Espresso needs the user unlocked)
./scripts/unlock-sim.sh [pin|puk] [device] # unlocks a SIM asking for its PIN or PUK
sudo ./scripts/install-adb-systemd-service.sh  # Device Tests runner host: adb as a systemd service
```

Skip the pre-push hook with `SKIP_PRE_PUSH_TESTS=1 git push`.

## Architecture

A full rewrite is specified in `docs/process/plans/rewrite-plan.md` — module graph, library decisions, data-compatibility contract, phases and the rules an executing agent follows. Read it before any structural change. Until its Phase 0 has merged, the rules below are what applies.

Two UI patterns coexist while the screens migrate, one at a time, from the first to the second. The helpers listed below are the stable core under both and are not being rewritten.

**Legacy screens** — activity-based with view binding; UI logic in the activity, business logic in helpers. `MainActivity` (~1.7k lines) and `SettingsActivity` (~1k lines) are the two that grew past what this pattern carries. Do not add UI logic to either: a feature that would touch them gets its own state holder and screen in the pattern below, mounted into the existing activity.

**Migrated screens** — a `ViewModel` (`viewModelScope` + `StateFlow`) per screen owns the state, and a Compose screen renders it, mounted in the existing activity through `ComposeView` so navigation and intents keep working. Compose is not in `app/build.gradle.kts` yet: the first migration PR adds the Compose BOM, and no Compose code is written before that lands.

**Migration order:** `SettingsActivity` pages first (self-contained, no SSH session), then `MainActivity`'s Plays tab, then its host list, then the Terminal tab last. `TerminalView` is a custom `AppCompatTextView` and stays as it is — a Compose screen that needs it wraps it in `AndroidView`. It is never rewritten in Compose: text selection, the IME connection and span rendering are the riskiest part of the app, and 38 releases of fixes live in it.

**Each migration is its own PR that leaves the app releasable.** The screen's instrumented tests move with it (`createAndroidComposeRule` in place of Espresso view matchers), and its ViewModel gets JVM unit tests. That is the point of the exercise: logic that today can only be verified on a device becomes testable in `testDebugUnitTest`.

**Key helpers:**
- `SshClient` — JSch wrapper; handles password/key auth, jump servers, PTY sessions. JSch classes are kept in ProGuard (`proguard-rules.pro`) because JSch loads crypto providers via reflection.
- `GeminiClient` — Gemini API over raw `HttpURLConnection` (no SDK).
- `DriveAuthManager` / `DriveLogUploader` — Google OAuth + Drive API for log uploads.
- `PlayRunner` — Executes automated scripts ("Plays") with `{{ PARAM }}` template placeholders.
- `SecurePrefs` — AES256-GCM encrypted SharedPreferences; use for all secrets (API keys, tokens).
- `PhraseDatabaseHelper` / `PlayDatabaseHelper` — SQLite via `SQLiteOpenHelper`. `PhraseDatabaseHelper` exposes a `MutableStateFlow` for reactive UI updates.
- `ConsoleLogRepository` / `TerminalLogRepository` — Session log persistence.

**UI navigation:**
- `MainActivity` — `ViewPager2` with Terminal and Plays tabs; complex page binding setup in `onCreate`.
- `SettingsActivity` — `ViewPager2` carousel with General, SSH, Gemini, Drive pages.
- `TerminalActivity` — interactive SSH terminal using the custom `TerminalView`.

**Threading:** Legacy code uses `Thread { ... }` / `runOnUiThread { ... }`. Newer activity code uses `lifecycleScope.launch` + `Dispatchers.IO`; ViewModels use `viewModelScope`. Do not mix styles within a new feature; follow the existing pattern in the file you're editing.

**Data storage:** `SecurePrefs` for secrets, standard `SharedPreferences` for non-sensitive settings, SQLite for phrases/plays.

## Coding conventions

- Kotlin official style, 4-space indent, no wildcard imports.
- `val` by default; `var` only when mutation is required.
- Null safety: prefer non-null, early-return on null with `orEmpty()` for strings.
- Error handling at module boundaries: `runCatching { ... }.getOrElse { ... }`.
- All user-visible strings in `app/src/main/res/values/strings.xml`.
- View binding (`binding.*`) instead of `findViewById` in legacy screens; Compose screens use neither.
- One top-level class per file.
- Resource IDs: `lower_snake_case`; layout files: `activity_*.xml`.

## Adding features

- New settings → a Compose page with its own ViewModel, mounted in `SettingsActivity` — not a new block in the activity. Secrets in `SecurePrefs`.
- New dependencies → `app/build.gradle.kts`.
- New permissions → `AndroidManifest.xml` (only when necessary).
- JSch crypto classes referenced only by name → add to `proguard-rules.pro` to prevent stripping.

## Build types

- `debug` — standard debug.
- `release` — minified with ProGuard; signing via env vars (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
- `minifiedDebug` — debug APK with minification enabled; used for instrumented tests to catch ProGuard issues.

## SSH test credentials

Stored in `.local/local-ssh-test.env` (chmod 600, git-ignored). Set up via `./scripts/setup-local-ssh-test.sh`.
