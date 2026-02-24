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
- Google Drive logs: create an OAuth client for the package `com.sushi.sshclient` and enable the Drive API.

Build a debug APK:
```bash
./gradlew assembleDebug
```

If you do not have the Gradle wrapper JAR yet, generate it once with:
```bash
gradle wrapper
```

## License
Apache-2.0. See `LICENSE`.
