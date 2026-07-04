# Testing & CI improvements

## Where we are

- **JVM unit tests:** 4 files (`CommandSafetyTest`, `SetupChecklistTest`,
  `ConnectionFailureClassificationTest`, `ExampleUnitTest`). Good targets,
  tiny surface.
- **Instrumented tests:** a solid device suite (`DeviceQaSuiteTest`,
  `JschRuntimeTest`, `TerminalViewSelectionTest`, `LocalSshIntegrationTest`,
  …) including a minified build type to catch R8 issues — this is above
  average and worth keeping.
- **CI:** `android-ci.yml`, `device-tests.yml`, `release.yml`, plus UX
  workflows; dependabot active; `lint-baseline.xml` present.

The gap: almost all *logic* lives in classes that need a device to test, so the
fast feedback loop covers very little.

## 1. Make core logic JVM-testable, then test it (P1)

The pattern that works here (see `CommandSafety`) is: pure Kotlin object, no
Android imports, exhaustive JVM tests. Apply it to:

| Logic | Today | Extraction |
|-------|-------|------------|
| ANSI/SGR parsing | private in `TerminalView` | `AnsiParser` pure class → test color runs, malformed sequences, the `\r` progress-bar case |
| Shell line splitting | private in `SshClient` (`processShellChar`, `readShellOutput`) | already nearly pure — make internal and test CR/LF/CRLF matrices |
| Play template substitution | `PlayRunner` | test `{{ PARAM }}` expansion, missing params, defaults |
| Host list (de)serialization + jump resolution + migration | `SshSettings` (needs `SecurePrefs`) | inject a plain in-memory `SharedPreferences` fake; test `migrateOldSettingsIfNeeded`, `deleteHost` jump-reference cleanup, corrupt-JSON behaviour |
| Gemini response parsing (`EXECUTE:` directive extraction) | `GeminiClient.parseCommand` | extract parser; test directive forms, multi-line responses, junk |

**Effort.** Each row is a ½–1 day PR; the extractions double as the
architecture cleanup in [doc 4](04-architecture-code-quality.md).

## 2. Static analysis in CI (P2)

- Add **ktlint** (style, matches the "Kotlin official style" convention already
  in CLAUDE.md) and **detekt** (complexity, swallowed exceptions — it would
  have flagged several findings in these documents). Start with autocorrect +
  a generated baseline so the first PR is not a 5000-line diff.
- **Burn down `lint-baseline.xml`**: schedule one small PR per release that
  removes a category from the baseline and fixes the underlying warnings.
- Treat new lint/detekt findings as CI failures once baselines exist.

**Effort.** Wiring ~1 day; burn-down amortized.

## 3. Coverage signal (P3)

Add JaCoCo (or Kover) to `testDebugUnitTest` and publish the report as a CI
artifact. Don't gate on a percentage yet — with 4 test files a gate is noise —
but make the number visible so the item-1 extractions show progress.

## 4. Regression tests to add alongside specific fixes

These pair with proposals in the other documents — write the test in the same
PR as the fix:

- **Host key verification** ([doc 1](01-security.md)): instrumented test that a
  changed server key aborts the connection (`JschRuntimeTest` is the natural
  home; a second `sshd` container key in the local SSH test rig covers it).
- **Passphrase keys** ([doc 1](01-security.md)): connect with an encrypted
  ed25519 key in `LocalSshIntegrationTest`.
- **Exit-status race** ([doc 4](04-architecture-code-quality.md) §3): exec a
  command through a deliberately slow channel and assert the real exit code is
  reported.
- **Reconnect/backoff** ([doc 3](03-connection-reliability.md)): JVM test the
  backoff scheduler as a pure class (clock injected).
- **JSch upgrades**: extend `ConnectionFailureClassificationTest` with the raw
  message strings of each JSch release when dependabot bumps it, so silent
  reclassification is caught at review time.

## 5. CI ergonomics (P3)

- Cache the NDK/CMake setup step if `android-ci.yml` doesn't already; native
  configure is typically the slowest cold step.
- Run `testDebugUnitTest` + lint on PRs (fast lane) and reserve the device
  suite for merge queue/nightly — if not already split, the workflow files
  should make the two lanes explicit.
