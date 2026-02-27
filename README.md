# sushi - SSH client

An open source Android SSH client focused on fast connections, clean session management, and a modern UI.

## Status
- Early scaffold with placeholder UI.
- SSH engine and host management are planned.
- Optional Gemini voice command mode (user-provided API key).
- Optional Google Drive log uploads (Google sign-in required).

## Development
Prerequisites:
- Android Studio (Hedgehog or newer recommended)
- JDK 17

Optional integrations:
- Gemini voice mode: add your API key in app settings.
- Google Drive logs: create an OAuth client for the package `net.hlan.sushi` and enable the Drive API.

Build a debug APK:
```bash
./gradlew assembleDebug
```

Run the optional local SSH integration tests on a connected device (not for CI):

1) Create a local git-ignored config via interactive wizard:
```bash
./scripts/setup-local-ssh-test.sh
```

This writes secrets to `.local/local-ssh-test.env` (chmod 600, git-ignored).

2) Run the tests:
```bash
./scripts/run-local-ssh-test.sh
```

You can still bypass the file and pass values as environment variables when needed.

If credentials are not set, `LocalSshIntegrationTest` is skipped (JUnit assumption), not failed.

You can still run Gradle directly if needed:
```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.hlan.sushi.LocalSshIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.sshHost=YOUR_HOST \
  -Pandroid.testInstrumentationRunnerArguments.sshPort=22 \
  -Pandroid.testInstrumentationRunnerArguments.sshUsername=YOUR_USER \
  -Pandroid.testInstrumentationRunnerArguments.sshPassword=YOUR_PASSWORD
```

Local checks before push:
```bash
./scripts/install-git-hooks.sh
```
This installs a pre-push hook that runs `./gradlew testDebugUnitTest`. To skip once: `SKIP_PRE_PUSH_TESTS=1 git push`.

If you do not have the Gradle wrapper JAR yet, generate it once with:
```bash
gradle wrapper
```

## License
Apache-2.0. See `LICENSE`.
