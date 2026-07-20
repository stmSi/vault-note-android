# VaultNote

VaultNote is an offline-first native Android application for organizing private notes, documents, and media. The current repository contains the Phase 2 foundation: the local note workflow, Room source of truth, persistent synchronization queue, and a defensive app-private attachment import pipeline.

This is deliberately an intermediate release. Note content is plaintext in Room, and imported files and thumbnails use attachment format version `0`, meaning private internal storage without encryption. Biometric locking, Android Keystore-backed attachment encryption, production synchronization, backup, and restore must be completed before VaultNote is presented as a finished secure vault.

## Phase 2 capabilities

- Kotlin Android application with one `:app` module, one activity, XML layouts, View Binding, and Fragments.
- Bounded, observable Notes, Archived, and Trash windows rendered by `RecyclerView`, `ListAdapter`, stable item IDs, and `DiffUtil`, with recoverable Previous/Next navigation.
- Plain-text note creation and editing with immediate in-memory updates.
- Version-aware autosave after 400 milliseconds of inactivity, plus an explicit flush when leaving the editor or when its host stops.
- Pin, favorite, archive, soft-delete, and restore operations.
- Room entities and indexes for vault items, attachments, tags, item/tag relationships, search documents, FTS, sync operations, sync state, and application settings.
- Transactional local mutations: an edit, revision change, search-document refresh, and sync-queue update commit together.
- A persistent, coalescing sync queue. The current fake scheduler records/coalesces work requests but performs no network access and never falsely marks an operation synchronized.
- Immutable screen state covering loading, empty, content, and error states.
- Unit-test support and Room schema export/migration-test infrastructure.
- Dependency locking, a checksum-pinned Gradle wrapper, minified/resource-shrunk release builds, and externally supplied release signing.
- Android Photo Picker, Storage Access Framework document selection, and system-camera capture without storage or camera permissions.
- Incoming `ACTION_SEND` and `ACTION_SEND_MULTIPLE` shares for supported files, text, and links, reviewed through an in-memory import preview.
- Defensive filename normalization, extension/MIME/signature agreement, bounded ZIP-container validation, a 100 MiB streaming limit, low-space checks, and same-note checksum deduplication.
- Atomic app-private file writes with SHA-256 calculated during the copy, a durable Room cleanup journal for process-death recovery, and no binary payloads in Room.
- Bounded image/PDF metadata probes and background thumbnail generation with sampled decoding and limited parallelism.
- Attachment metadata and thumbnail lists in the editor, plus a viewer boundary that grants another app temporary read access only when requested.
- An explicit Room `1 → 2` migration that preserves Phase 1 data while adding image dimensions and PDF page counts.

Encrypted attachment storage, app locking, search UI, OCR, WorkManager-backed remote synchronization, conflict UI, backup/restore, and performance profile modules remain outside Phase 2. See [Architecture](docs/architecture.md) for boundaries and the staged design.

## Project layout

```text
VaultNote/
├── app/
│   ├── schemas/                         # Exported Room schemas
│   └── src/
│       ├── main/
│       │   ├── java/com/vaultnote/
│       │   │   ├── app/                # Application, activity, DI container
│       │   │   ├── core/               # Database, files, repository, sync, common code
│       │   │   └── feature/            # Vault, editor, import, and viewer screens
│       │   └── res/                     # XML layouts, theme, strings, drawables
│       ├── test/                        # JVM unit tests
│       └── androidTest/                 # Room/instrumentation tests
├── docs/
│   └── architecture.md
├── gradle/libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

Only `:app` is present through Phase 2. The `:baselineprofile` and `:benchmark` modules are planned for the performance phase, when they can exercise stable release-like journeys instead of profiling temporary scaffolding.

## Toolchain

The checked-in build is configured for:

- Android SDK compile/target API 37 and minimum API 26
- Java 17 bytecode/toolchain compatibility
- Android Gradle Plugin 9.2.1 and Gradle 9.4.1
- AGP built-in Kotlin with KSP2 for Room code generation
- Kotlin DSL and a Gradle version catalog

Install JDK 17 and Android SDK Platform 37, then point `local.properties` at the local Android SDK if Android Studio has not already done so. `local.properties`, signing material, credentials, and secrets must remain uncommitted.

## Build and verify

Run these commands from the repository root:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
```

Run device tests, including Room schema validation, on an API 26-or-newer emulator or connected device:

```bash
./gradlew connectedDebugAndroidTest
```

These are the commands to execute; this document does not assert that they have passed in a particular checkout or environment. Release artifacts are minified and resource-shrunk, but distributable release signing should be supplied outside the repository.

## Architecture in brief

```text
XML View / Fragment
        ↓
     ViewModel
        ↓
  VaultRepository
        ↓
Room transaction ─── search aggregate / FTS
        ↓
persistent sync operation
        ↓
fake coalescing scheduler (through Phase 2)
```

Room is always the source displayed by the UI. A future backend may update Room through synchronization, but it will never feed a screen directly. Database and file work runs off the main thread, and optional systems are not initialized before the first local screen is drawn.

## Data and privacy warning

Phase 2 does not yet implement the final security model. In particular:

- note text, tag names, and the FTS index are plaintext inside the app-private Room database;
- attachment bytes and derived thumbnails are confined to app-private storage but remain plaintext with encryption format version `0`;
- the fake sync implementation does not back up or upload data;
- there is no biometric/device-credential gate or secure recent-apps treatment yet;
- Android sandboxing does not defend against a rooted device, a compromised OS, or extraction from an unlocked device.

The project must not log note bodies, search text, credentials, keys, or other private payloads. Future phases add Android Keystore-protected AES-256-GCM attachment encryption, lock controls, a replaceable sync implementation, encrypted backup/restore, and their corresponding threat-model documentation.

## Database evolution

Room schema export is enabled and exported schema JSON is version-controlled. Production builds never enable destructive migration fallback. Version 2 adds nullable image width, image height, and PDF page-count metadata plus a non-sensitive attachment-file cleanup journal through an explicit `1 → 2` migration. Migration tests create a version 1 fixture, migrate it, validate the current schema, and verify retained item and attachment data.

## Development principles

- Preserve local-first behavior: persist locally before requesting synchronization.
- Keep I/O and Room transactions off the main thread.
- Do not load an unbounded vault or large binary data into memory.
- Keep `Application.onCreate()` lightweight and defer optional initialization.
- Pass dependencies through constructors or the small application container; do not add a reflection-based DI framework without a demonstrated need.
- Treat cancellation distinctly from failure and avoid swallowing either.
- Never add hardcoded credentials, broad storage permissions, `MANAGE_EXTERNAL_STORAGE`, or sensitive logging.
- Add narrow migrations and focused tests whenever the schema or transactional invariants change.
