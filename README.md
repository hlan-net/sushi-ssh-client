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

Run the optional local SSH integration test on a connected device (not for CI):
```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.hlan.sushi.LocalSshIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.sshHost=YOUR_HOST \
  -Pandroid.testInstrumentationRunnerArguments.sshPort=22 \
  -Pandroid.testInstrumentationRunnerArguments.sshUsername=YOUR_USER \
  -Pandroid.testInstrumentationRunnerArguments.sshPassword=YOUR_PASSWORD
```

You can pass `sshPrivateKey` instead of `sshPassword` if you want key-based auth.

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
