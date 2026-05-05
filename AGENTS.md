# AGENTS.md

Sushi is an Android SSH client. Package `net.hlan.sushi`, single Gradle module `:app`, Kotlin DSL, JDK 17, min SDK 26, target SDK 36. No Compose, no ViewModel/MVVM — activity-based with view binding.

`CLAUDE.md` covers the same ground in more detail; keep the two reconciled when changing one.

## Build / test

```bash
./gradlew assembleDebug
./gradlew lintDebug
./gradlew testDebugUnitTest                       # JVM unit tests
./gradlew testDebugUnitTest --tests "net.hlan.sushi.ExampleUnitTest.testAddition_isCorrect"
./gradlew connectedDebugAndroidTest               # requires device/emulator
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.hlan.sushi.JschRuntimeTest
```

Reports: `app/build/reports/`. Version overrides: `-PversionCode=...`, `-PversionName=...`.

## Machine setup (non-obvious)

- `local.properties` is gitignored; each machine must set `sdk.dir=/absolute/path/to/sdk`.
- Required SDK packages: `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`, `ndk;27.0.12077973`, `cmake;3.22.1`.
- If native builds fail, validate NDK clang directly at `.../ndk/27.0.12077973/toolchains/llvm/prebuilt/.../bin/clang --version` and install missing host compatibility libs.

## Build types (non-obvious)

- `debug` — standard.
- `release` — minified, signed via env: `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- `minifiedDebug` — debug + R8/ProGuard. **`testBuildType = "minifiedDebug"`**, so `connectedAndroidTest` runs against the minified APK to catch ProGuard stripping.

JSch reflectively loads crypto providers — its classes are kept in `app/proguard-rules.pro`. When adding any code referenced only by name/reflection, add keep rules there or `minifiedDebug` will break.

## Local scripts

```bash
./scripts/install-wifi-debug.sh [device]   # build + install via Wi-Fi ADB; auto-bumps versionCode
./scripts/run-device-qa-suite.sh           # instrumented QA suite
./scripts/setup-local-ssh-test.sh          # wizard; writes .local/local-ssh-test.env (chmod 600, gitignored)
./scripts/run-local-ssh-test.sh            # runs LocalSshIntegrationTest using above creds
./scripts/install-git-hooks.sh             # installs pre-push hook (runs unit tests)
```

Bypass pre-push hook: `SKIP_PRE_PUSH_TESTS=1 git push`.

## Architecture

UI logic stays in activities; service logic lives in helpers:

- `SshClient` — JSch wrapper (password/key auth, jump servers, PTY).
- `GeminiClient` — Gemini API over raw `HttpURLConnection` (no SDK).
- `DriveAuthManager` / `DriveLogUploader` — Google OAuth + Drive log upload.
- `PlayRunner` — runs automated "Plays" with `{{ PARAM }}` template placeholders.
- `SecurePrefs` — AES256-GCM encrypted prefs; **all secrets go here**.
- `PhraseDatabaseHelper` / `PlayDatabaseHelper` — `SQLiteOpenHelper`. `PhraseDatabaseHelper` exposes a `MutableStateFlow` for reactive UI.
- `ConsoleLogRepository` / `TerminalLogRepository` — session log persistence.

Main UI: `MainActivity` is a `ViewPager2` (Terminal + Plays tabs); `SettingsActivity` is a `ViewPager2` carousel (General/SSH/Gemini/Drive); `TerminalActivity` uses the custom `TerminalView`.

## Threading

Mixed by design: legacy code uses `Thread { } / runOnUiThread { }`, newer code uses `lifecycleScope.launch + Dispatchers.IO`. **Match the surrounding file's style**; do not mix in a single feature.

## Conventions worth knowing

- Kotlin official style, 4-space indent, no wildcard imports, one top-level class per file.
- All user-visible strings → `app/src/main/res/values/strings.xml`.
- View binding only (`binding.*`), never `findViewById`.
- Resource IDs `lower_snake_case`; layouts `activity_*.xml`.
- Error boundaries: `runCatching { }.getOrElse { }` mapping to small result data classes (`GeminiResult`, `DriveUploadResult`).
- New settings → add UI to `SettingsActivity` + store in `SecurePrefs` if sensitive.
- New deps → `app/build.gradle.kts`. New permissions → `AndroidManifest.xml` only when required.

## Tests

- Avoid live network in tests; SSH integration tests read from `.local/local-ssh-test.env` (never commit).
- Instrumented tests run against `minifiedDebug` — failures may be ProGuard-related, not logic bugs.
