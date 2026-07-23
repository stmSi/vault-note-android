# VaultNote

VaultNote is organized as a multi-platform workspace.

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
