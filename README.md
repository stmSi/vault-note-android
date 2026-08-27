# VaultNote

VaultNote is organized as a multi-platform workspace.

## Download

- [Download the latest Android APK](https://github.com/stmSi/vault-note-android/releases/latest/download/VaultNote-Android.apk)
- [Download the latest Linux AppImage](https://github.com/stmSi/vault-note-android/releases/latest/download/VaultNote-Desktop-x86_64.AppImage)
- [Download the latest Debian/Ubuntu package](https://github.com/stmSi/vault-note-android/releases/latest/download/VaultNote-Desktop-amd64.deb)
- [Download the latest Fedora/RHEL package](https://github.com/stmSi/vault-note-android/releases/latest/download/VaultNote-Desktop-x86_64.rpm)
- [Browse every release and checksum](https://github.com/stmSi/vault-note-android/releases/latest)

`VaultNote-Android.apk` is production-signed when the repository signing secrets are configured. Otherwise, it is an installable debug build; unsigned optimized APKs remain available on the release page for verification.

## Platforms

- [Android](android/) — the existing native Android application.
- [Desktop](desktop/) — the Tauri client for Windows and Linux.

## Android build

Run the Android build from the `android` directory:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

See the [Android project README](android/README.md) for its architecture, security model, toolchain, and complete verification commands.

## Desktop build

Run the desktop checks from the `desktop` directory:

```bash
cd desktop
npm install
npm run check
npm test
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
```

Run the desktop client in development with:

```bash
cd desktop
npm install
npm run tauri dev
```

Build Linux `.deb`, `.rpm`, and `.AppImage` packages with:

```bash
cd desktop
npm run bundle:linux
```

See the [desktop project README](desktop/README.md) for platform prerequisites and its security boundary.
