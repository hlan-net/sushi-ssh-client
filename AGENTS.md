# AGENTS.md

Sushi is an Android SSH client. Package `net.hlan.sushi`, single Gradle module `:app`, Kotlin DSL, JDK 17, min SDK 26, target SDK 36. Two UI patterns coexist: legacy screens are activity-based with view binding; new and migrated screens are Compose + ViewModel, mounted in the existing activity. No new UI logic goes into `MainActivity` or `SettingsActivity`. `CLAUDE.md` → Architecture has the rules and the migration order; `docs/process/plans/rewrite-plan.md` is the reference plan for a full rewrite (not committed).

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
./scripts/check-versions.sh                # every dependency vs latest stable (docs/process/DEPENDENCY_LIFECYCLE.md)
```

Bypass pre-push hook: `SKIP_PRE_PUSH_TESTS=1 git push`.

## Architecture

Legacy screens keep UI logic in the activity; new and migrated screens put state in a `ViewModel` (`viewModelScope` + `StateFlow`) and render it in Compose. Service logic lives in helpers either way:

- `SshClient` — JSch wrapper (password/key auth, jump servers, PTY).
- `GeminiClient` — Gemini API over raw `HttpURLConnection` (no SDK).
- `DriveAuthManager` / `DriveLogUploader` — Google OAuth + Drive log upload.
- `PlayRunner` — runs automated "Plays" with `{{ PARAM }}` template placeholders.
- `SecurePrefs` — AES256-GCM encrypted prefs; **all secrets go here**.
- `PhraseDatabaseHelper` / `PlayDatabaseHelper` — `SQLiteOpenHelper`. `PhraseDatabaseHelper` exposes a `MutableStateFlow` for reactive UI.
- `ConsoleLogRepository` / `TerminalLogRepository` — session log persistence.

Main UI: `MainActivity` is a `ViewPager2` (Terminal + Plays tabs); `SettingsActivity` is a `ViewPager2` carousel (General/SSH/Gemini/Drive); `TerminalActivity` uses the custom `TerminalView`, which is not rewritten during the migration (a Compose screen wraps it in `AndroidView`). Migration order: the Gemini conversation first (it is an `AlertDialog` in `MainActivity` today), then Settings pages, then the Plays tab, the host list, the Terminal tab last.

## Threading

Mixed by design: legacy code uses `Thread { } / runOnUiThread { }`, newer code uses `lifecycleScope.launch + Dispatchers.IO`. **Match the surrounding file's style**; do not mix in a single feature.

## Conventions worth knowing

- Kotlin official style, 4-space indent, no wildcard imports, one top-level class per file.
- All user-visible strings → `app/src/main/res/values/strings.xml`.
- View binding (`binding.*`) in legacy screens, never `findViewById`; Compose screens use neither.
- Resource IDs `lower_snake_case`; layouts `activity_*.xml`.
- Error boundaries: `runCatching { }.getOrElse { }` mapping to small result data classes (`GeminiResult`, `DriveUploadResult`).
- New settings → a Compose page with its own ViewModel mounted in `SettingsActivity`, not a new block in the activity; secrets in `SecurePrefs`.
- Anything that changes a layout, a user-visible string or a screen's states starts from a Figma proposal card and links it in the PR: `docs/process/UX_PROPOSALS.md`. The UX Gate CI check fails without the link or an explicit no-UI declaration.
- New deps → `app/build.gradle.kts`. New permissions → `AndroidManifest.xml` only when required.

## Tests

- Avoid live network in tests; SSH integration tests read from `.local/local-ssh-test.env` (never commit).
- Instrumented tests run against `minifiedDebug` — failures may be ProGuard-related, not logic bugs.
