# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Sushi is an Android SSH client (package `net.hlan.sushi`, min SDK 24, target SDK 36). Single Gradle module `:app`, Kotlin DSL, JDK 17 required.

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

### Local dev scripts

```bash
./scripts/install-wifi-debug.sh [device]   # build + install via Wi-Fi ADB (auto-bumps versionCode)
./scripts/run-device-qa-suite.sh           # runs instrumented QA suite on device
./scripts/setup-local-ssh-test.sh          # wizard to store SSH test credentials
./scripts/run-local-ssh-test.sh            # runs LocalSshIntegrationTest
./scripts/install-git-hooks.sh             # installs pre-push hook (runs unit tests)
```

Skip the pre-push hook with `SKIP_PRE_PUSH_TESTS=1 git push`.

## Architecture

Activity-based (no Compose, no ViewModel/MVVM). UI logic stays in activities; business logic goes into helper classes.

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

**Threading:** Legacy code uses `Thread { ... }` / `runOnUiThread { ... }`. Newer code uses `lifecycleScope.launch` + `Dispatchers.IO`. Do not mix styles within a new feature; follow the existing pattern in the file you're editing.

**Data storage:** `SecurePrefs` for secrets, standard `SharedPreferences` for non-sensitive settings, SQLite for phrases/plays.

## Coding conventions

- Kotlin official style, 4-space indent, no wildcard imports.
- `val` by default; `var` only when mutation is required.
- Null safety: prefer non-null, early-return on null with `orEmpty()` for strings.
- Error handling at module boundaries: `runCatching { ... }.getOrElse { ... }`.
- All user-visible strings in `app/src/main/res/values/strings.xml`.
- View binding (`binding.*`) instead of `findViewById`.
- One top-level class per file.
- Resource IDs: `lower_snake_case`; layout files: `activity_*.xml`.

## Adding features

- New settings → `SettingsActivity` + store secrets in `SecurePrefs`.
- New dependencies → `app/build.gradle.kts`.
- New permissions → `AndroidManifest.xml` (only when necessary).
- JSch crypto classes referenced only by name → add to `proguard-rules.pro` to prevent stripping.

## Build types

- `debug` — standard debug.
- `release` — minified with ProGuard; signing via env vars (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
- `minifiedDebug` — debug APK with minification enabled; used for instrumented tests to catch ProGuard issues.

## SSH test credentials

Stored in `.local/local-ssh-test.env` (chmod 600, git-ignored). Set up via `./scripts/setup-local-ssh-test.sh`.
