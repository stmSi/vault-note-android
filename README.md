# VaultNote

VaultNote is an offline-first native Android application for private notes and imported files.

VaultNote is not a zero-knowledge vault. Imported attachments and thumbnails are encrypted with AES-256-GCM, but note titles, bodies, tags, attachment display names, extracted OCR, and the FTS index remain plaintext in the app-private Room database. The included backend is an in-memory fake, not cloud backup. Encrypted backup/restore and performance modules are not included.

## Features

- Kotlin, one activity, XML Views, View Binding, Fragments, and lightweight manual dependency injection.
- One-handed bottom navigation for Notes, Archive, Trash, Search, and Settings; contextual screens retain only their own back/actions.
- Bounded Notes, Archived, and Trash windows using `RecyclerView`, `ListAdapter`, stable IDs, and `DiffUtil`.
- Plain-text note editing with immediate immutable UI state, 400 ms debounced autosave, and lifecycle/navigation flush.
- Pin, favorite, item/title color coding, archive, soft-delete, restore, tags, Room FTS, and transactional persistent sync intent.
- Unique network-aware WorkManager synchronization, persistent leases/retries, version tokens, attachment-first uploads, incremental cursors, status UI, and explicit conflict copies/resolution behind replaceable interfaces.
- Photo Picker, Storage Access Framework, system camera, and `ACTION_SEND`/`ACTION_SEND_MULTIPLE` imports without broad storage or camera permissions.
- Defensive filename, MIME, extension, signature, UTF-8, ZIP-container, size, space, and path validation.
- Streaming SHA-256, bounded metadata parsing, sampled background thumbnails, cleanup journaling, and no large blobs in Room.
- Android Keystore AES-256 keys and versioned AES-GCM envelopes for every new attachment and thumbnail.
- Unique provider-generated nonces, authenticated header/record/purpose metadata, atomic writes, and authentication before plaintext output.
- Resumable format-0 migration after unlock, with original checksum validation before encryption and bounded batches.
- Optional BiometricPrompt/device-credential lock with immediate, 30-second, 1-minute, or 5-minute background timeout.
- Hidden recent-apps previews, configurable screenshot blocking where the platform permits it, and an opaque accessible lock overlay.
- A non-exported decrypting content provider with real filename/MIME/size metadata, seekable authenticated handoffs, and random bounded grants for explicit external viewing or sharing.
- Explicit attachment “Save a copy” through the Storage Access Framework and attachment sharing through the Android Sharesheet; failed saves remove or truncate partial plaintext output where the destination provider permits it.
- 120 ms debounced per-character Room FTS prefix search over titles, note bodies, tags, attachment filenames, and extracted OCR text, with highlighted snippets and live incremental results.
- Lazy bundled ML Kit Latin OCR for images and PDFs, sampled/bounded processing, persistent retry states, checksum-based unchanged-file avoidance, and atomic FTS updates.
- Unit tests for core repository behavior, autosave, import defenses, encryption corruption/rotation, locking, grants, and migration invariants.
- Exported Room schemas and explicit `1 → 2 → 3` migration tests; version 3 adds a neutral-default item color.
- Dependency locking, checksum-pinned Gradle wrapper, minified/resource-shrunk release builds, and external release-signing support.

## Project layout

```text
VaultNote/
├── app/
│   ├── schemas/
│   └── src/
│       ├── main/java/com/vaultnote/
│       │   ├── app/                  # Application, activity, manual container
│       │   ├── core/
│       │   │   ├── database/         # Room entities, DAOs, migrations
│       │   │   ├── encryption/       # Envelope and Keystore implementation
│       │   │   ├── files/            # Validation, storage, thumbnails
│       │   │   ├── repository/       # Local-first data operations
│       │   │   ├── security/         # Lock state and secure content provider
│       │   │   ├── search/           # Bounded FTS query compiler and repository
│       │   │   ├── ocr/              # Lazy OCR engine and durable pipeline
│       │   │   └── sync/             # Protocol, queue consumer, fake backend, WorkManager scheduler
│       │   └── feature/              # Vault, editor, import, viewer, lock, settings
│       ├── test/                      # JVM/Robolectric tests
│       └── androidTest/               # Room migration/instrumentation tests
├── docs/
│   ├── architecture.md
│   ├── encryption-format.md
│   ├── performance.md
│   ├── security-model.md
│   ├── testing.md
│   ├── sync-protocol.md
│   └── threat-model.md
├── gradle/libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

## Toolchain

- Android SDK compile/target API 37, minimum API 26
- Java 17
- Android Gradle Plugin 9.2.1 and Gradle 9.4.1
- AGP built-in Kotlin with KSP2 for Room
- Kotlin DSL and Gradle version catalog

Install JDK 17 and Android SDK Platform 37 in their normal user/tool locations. `local.properties` may point to the local SDK but is ignored. SDKs, JDKs, Gradle caches, signing material, credentials, and secrets are never installed or saved inside the repository.

## Build and verification

From the repository root:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
```

With an API 26-or-newer emulator or device:

```bash
./gradlew connectedDebugAndroidTest
```

The installable debug artifact is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release builds are minified and resource-shrunk. Distributable release signing must be supplied externally; local signing files and passwords must not enter source control.

## Architecture in brief

```text
XML Fragment/View
       ↓
   ViewModel
       ↓
  Repository
       ↓
Room transaction + encrypted internal files
       ↓
persistent sync operation
       ↓
unique WorkManager job
       ↓
replaceable protocol interfaces → in-memory development backend
```

Room remains the displayed source of truth. Local edits commit before scheduling, and no initial network, OCR, thumbnail, backup, cleanup, hash, or encryption migration blocks the first frame. Optional security maintenance and OCR begin only after the vault has been unlocked and displayed.

## Security boundary

Attachment format `1` is a documented binary AES-256-GCM envelope. Each file authenticates its format/key version, nonce, length, attachment ID, and attachment-versus-thumbnail purpose. The reader verifies the whole envelope in a first streaming pass, then streams plaintext in a second pass through the same descriptor. External apps that require seeking receive a read-only descriptor to an app-private cache file only after verification; VaultNote immediately unlinks its name while the descriptor remains open and removes abandoned named files on later access.

The optional app lock authenticates with a strong biometric or device credential. Its in-process session gates UI and content-provider access; encryption keys are not configured for authentication on every read. This avoids deliberately tying attachment survival to biometric enrollment state, but it does not protect against code execution inside the unlocked app process.

Important residual risks:

- note/search/metadata text is plaintext in Room;
- rooted devices, OS compromise, debug extraction, malicious accessibility, or a compromised app process are outside the sandbox guarantee;
- an external viewer, share recipient, or user-selected save destination can retain plaintext after the user explicitly releases a file;
- there is no independent encrypted backup or key recovery yet;
- the included in-memory backend provides no remote durability and resets with the process;
- lock is optional and defaults off, while screenshot blocking defaults on;
- on Android 12L and older, concealing recents requires `FLAG_SECURE`, so screenshots remain blocked even if the setting is disabled.

Read [Security model](docs/security-model.md), [Encryption format](docs/encryption-format.md), and [Threat model](docs/threat-model.md) before treating the app as storage for sensitive material.

## Database evolution

Room schema JSON is version-controlled. Production never uses destructive migration fallback. Version 2 added media metadata and the cleanup journal; version 3 adds the non-null `color` column with `DEFAULT` for every existing item.

## Development principles

- Persist locally before requesting synchronization.
- Keep Room, file, crypto, and future network work off the main thread.
- Stream large data and keep lists/decoding bounded.
- Keep `Application.onCreate()` lightweight and optional dependencies lazy.
- Use explicit constructor injection and the small application container.
- Propagate cancellation and typed failures; never mark incomplete security work successful.
- Never add hardcoded credentials, broad storage permissions, `MANAGE_EXTERNAL_STORAGE`, sensitive logs, or plaintext keys.
- Preserve narrow migrations and add focused tests for security and transactional invariants.
