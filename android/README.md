# VaultNote for Android

Offline-first notes, checklists, attachments, search, and reminders.

**Read-only:** Use the note's **⋮** menu to turn protection on or off. Tap a line's lock to protect just that line while adding more content.

## Build

Requires Java 17 and Android SDK 37. Supports Android 8.0+ (API 26).

From this directory:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-universal-debug.apk`

Device tests: `./gradlew connectedDebugAndroidTest` with an emulator or device.

Details: [Architecture](docs/architecture.md) · [Testing](docs/testing.md) · [Security](docs/security-model.md)
