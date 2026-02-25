# AGENTS.md

This file guides agentic coding assistants working in this repository.
It summarizes build/test commands and local coding conventions.
## Project summary
- Android app built with Gradle (Kotlin DSL).
- Single module: `app`.
- Package: `net.hlan.sushi`.
- UI uses view binding (no Compose).
## Environment
- JDK 17 required.
- Android Studio Hedgehog or newer recommended.
- Gradle wrapper included (`./gradlew`).
## Build commands
Common builds:
```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleDebug
```
Clean build:
```bash
./gradlew clean assembleDebug
```
If Gradle wrapper JAR is missing:
```bash
gradle wrapper
```
## Lint / static analysis
Run lint for all variants:
```bash
./gradlew lint
```
Debug-only lint:
```bash
./gradlew lintDebug
```
Reports are written under `app/build/reports/`.
## Tests
Unit tests (JVM):
```bash
./gradlew test
./gradlew testDebugUnitTest
```
Run a single unit test method:
```bash
./gradlew testDebugUnitTest --tests "net.hlan.sushi.ExampleUnitTest.testAddition_isCorrect"
```
Instrumented tests (device/emulator):
```bash
./gradlew connectedAndroidTest
./gradlew connectedDebugAndroidTest
```
Run a single instrumented test class:
```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.hlan.sushi.ExampleInstrumentedTest
```
Run a single instrumented test method:
```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.hlan.sushi.ExampleInstrumentedTest#useAppContext
```
Device tests require an emulator or physical device.
## Kotlin & formatting
- Kotlin official style is enabled (`kotlin.code.style=official`).
- Indentation: 4 spaces, no tabs.
- Keep line length reasonable (IDE defaults).
- Prefer `val`; use `var` only when mutation is required.
- Prefer small, focused functions with early returns for guards.
- Expression-bodied functions are fine for simple returns.
- Avoid wildcard imports.
- Import order follows Android Studio defaults:
  - `android.*`, `androidx.*`, third-party (`com.*`), then `java.*`/`kotlin.*`.
- One top-level class per file.
## Types & nullability
- Use Kotlin null-safety instead of nullable globals.
- Prefer non-null values; handle null with early returns.
- Use `orEmpty()` for nullable strings.
- Use explicit types when inference would reduce clarity.

## Naming conventions
- Classes/objects: `UpperCamelCase`.
- Functions/properties: `lowerCamelCase`.
- Constants: `UPPER_SNAKE_CASE` in `companion object`.
- Resource IDs: `lower_snake_case`.
- Layout files: `activity_*.xml`.
- Test classes end with `Test`.

## UI & resources
- All user-visible text goes in `app/src/main/res/values/strings.xml`.
- Use view binding (`binding`) instead of `findViewById`.
- Keep activity setup in `onCreate`, state refresh in `onResume` when needed.
- Update UI only from the main thread.

## Threading & networking
- Never perform network or disk I/O on the UI thread.
- Current code uses `Thread { ... }` and `runOnUiThread { ... }`.
- If introducing coroutines, do it consistently and update this doc.
- Close network connections and streams in `finally`/`use` blocks.

## Error handling
- Use `runCatching { ... }` for background work and map to result objects.
- Catch `Exception` only at module boundaries (network, storage, API calls).
- Provide user-friendly messages; avoid leaking raw stack traces.
- Do not swallow failures without logging or UI feedback.

Example pattern:
```kotlin
val result = runCatching {
    doWork()
    true
}.getOrElse { error ->
    false
}
```

## Data storage & security
- Use `SecurePrefs` for secrets (API keys, tokens).
- Use regular `SharedPreferences` only for non-sensitive data.
- Avoid logging sensitive values.
- Prefer `applicationContext` in long-lived helpers.

## Architecture notes
- Keep UI logic in activities; move service logic into helper classes.
- Existing helpers: `GeminiClient`, `DriveAuthManager`, `DriveLogUploader`.
- Use small data classes for results (see `GeminiResult`, `DriveUploadResult`).

## Versioning & signing
- Version overrides via Gradle properties:
  - `-PversionCode=...`
  - `-PversionName=...`
- Release signing uses environment variables:
  - `ANDROID_KEYSTORE_PATH`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
- Keep credentials out of git and logs.

## Cursor/Copilot rules
- No Cursor rules found in `.cursor/rules/` or `.cursorrules`.
- No Copilot instructions found in `.github/copilot-instructions.md`.

## Agentic tips
- Follow existing patterns before introducing new frameworks.
- Update `app/build.gradle.kts` when adding dependencies.
- Add permissions to `AndroidManifest.xml` only when necessary.
- Prefer deterministic tests; avoid live network in tests.
- Add new settings to `SettingsActivity` and store them in `SecurePrefs`.
- Update `README.md` if new setup steps are required.
