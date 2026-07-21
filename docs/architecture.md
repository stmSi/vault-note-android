# VaultNote Phase 5 architecture

## Scope and quality boundary

Phase 5 extends the secured local foundation with durable WorkManager synchronization, replaceable remote interfaces, revision/token conflict detection, preserve-both conflict resolution, and sync status UI. The included remote implementation is an in-memory development fake, not cloud backup. Room note/search/metadata content is still plaintext, so attachment encryption is not presented as whole-vault encryption.

The current build has one `:app` module. That keeps startup, ownership, and build configuration straightforward while the product surface is small. Baseline Profile and Macrobenchmark modules remain deferred to Phase 7, after the measured journeys and release behavior are stable enough to make those profiles meaningful.

## Architectural rules

The principal data path is:

```text
MainActivity
  └── Fragment + XML/View Binding
        └── ViewModel (immutable UI state)
              ├── VaultRepository
              └── AttachmentRepository
                    ├── AttachmentFileManager
                    │     ├── validated, versioned encrypted file
                    │     └── encrypted bounded thumbnail
                    └── Room database transaction
                          ├── vault, attachment, and tag tables
                          ├── search aggregate and FTS index
                          └── persistent item/attachment operations
                                └── unique WorkManager job
                                      └── SyncRepository
                                            ├── SyncApi/AuthProvider
                                            └── RemoteFileStore
```

These rules are invariants, not conventions:

1. Room is the local source of truth. Screens collect `Flow` values derived from Room; a remote response will never be rendered directly.
2. Every user mutation is committed locally before sync is requested.
3. Related state changes are atomic. An item mutation, local revision increment, sync status, search aggregate refresh when needed, and queue coalescing share one Room transaction.
4. Scheduling happens only after the transaction commits. Scheduling failure leaves the durable operation available for recovery.
5. Main-thread work is limited to state reduction and view rendering. Database and future file/network operations use explicit injected dispatchers.
6. Lists are bounded and project only what the row needs. The app does not load all notes, attachments, or binary data into memory.
7. WorkManager is only a durable wake-up mechanism. A queue row, leased operation identity, verified remote result, and Room commit determine completion.
8. External filenames, MIME claims, sizes, and URIs are untrusted. Only a fully validated, checksummed internal copy may be referenced by Room.
9. RecyclerView binding performs no hash, metadata parsing, decryption, or thumbnail generation; rows receive prepared metadata and a secure thumbnail content URI.
10. Attachment plaintext is released only after full AES-GCM authentication and only through the non-exported secure provider while unlocked or under an explicit short-lived, bounded external grant.

## Application and dependency lifetime

`VaultNoteApplication` owns a lightweight manual dependency container. The container creates application-scoped dependencies such as the Room database, DAOs, repository, clock/ID facilities, dispatchers, and sync scheduler without retaining an `Activity`, `Fragment`, binding, or View.

Heavy or optional facilities are exposed lazily. `Application.onCreate()` performs no database cleanup, FTS rebuild, file hash, network request, authentication refresh, OCR initialization, image-loader initialization, encryption migration, or backup check. The attachment manager, crypto service, thumbnail generator, and Coil loader are first created only when their surfaces need them. Room itself may open when the first policy/list query requires it; no migration or file work is attached to scrolling or binding a list row.

ViewModels receive their dependencies through an explicit factory. This keeps construction visible and permits deterministic fake clocks, IDs, dispatchers, and schedulers in tests without a reflection-based dependency-injection framework.

## Navigation and presentation

The app is a single `MainActivity` with a primary fragment container plus an opaque lock-overlay container and direct `FragmentManager` navigation. The vault list is created only after policy loading permits access. The editor, import preview, attachment viewer, and security settings are shallow destinations. Fragment arguments persist only stable IDs and a non-sensitive in-memory import token. Shared text, external URIs, camera paths, and note drafts are never written into saved-state bundles. Incoming shares received while locked remain only in the activity-scoped coordinator until successful unlock; process death deliberately expires them. Camera capture saves only an opaque, format-validated UUID in `SavedStateHandle`. `onStop` still flushes the current note draft before the lock timeout is applied.

View Binding is scoped to the view lifecycle. A Fragment clears its binding in `onDestroyView`, and Flow collection is tied to `viewLifecycleOwner` with a started-state lifecycle gate. This prevents detached views and activities from being retained.

### Vault list

The list ViewModel exposes one immutable state with four meaningful render forms:

- loading while the first local query is unresolved;
- empty after a successful query with no matching items;
- content containing an immutable, bounded list of row models;
- error containing a safe user-facing message and a retry action only when retrying is meaningful.

The `RecyclerView` uses `ListAdapter` and `DiffUtil`. IDs derive from the persistent item identity and are stable across reordering. Content equality includes only properties displayed by the row. Each query returns a 100-row window plus one lookahead row; explicit Previous/Next controls change a bounded SQL `OFFSET` instead of accumulating the whole vault in memory. Section and page coordinates survive process recreation. Active notes order pinned items first and then recently updated items. No row binding performs database work, hashing, OCR, thumbnail generation, or attachment loading.

The activity-level bottom bar provides one-handed access to Notes, Archive, Trash, Search, and Settings. The primary vault toolbar is removed. Contextual editor/viewer/status screens keep only their own back and item actions. Archived rows can be opened or restored; trashed rows can be restored without exposing them to editing first. Soft deletion retains content and queued tombstone state, so neither archive nor trash is a one-way action.

### Note editor and autosave

Typing first updates immutable editor state in memory, so rendering is independent of database latency. The autosave controller assigns a monotonically increasing generation to edited title/body snapshots:

1. A changed snapshot replaces the pending snapshot immediately.
2. A coroutine waits for 400 milliseconds of inactivity.
3. Saves are serialized; a newer edit does not cancel an already-running Room transaction.
4. Completion only marks the generation it wrote as persisted. If a newer generation exists, that newer state remains dirty.
5. Explicit `flush()` cancels the pending delay, waits for any active save as needed, and persists the newest generation.

The editor flushes before an explicit navigation away and when its host stops, covering the application entering the background. The repository treats identical title/body content as a no-op, so a debounce followed immediately by a lifecycle flush cannot manufacture an extra local revision or duplicate queue operation. Autosave writes a persistent operation, but it does not enqueue a separate WorkManager request per keystroke; the scheduler signal is coalesced.

Pin, favorite, named item color, archive, soft-delete, and restore are explicit repository commands rather than whole-entity replacement. The selected color is rendered as a paired light/dark item surface and title color and is included in sync metadata. This prevents a stale editor snapshot from reverting an independently changed flag.

## Attachment import and viewing

Photo Picker, Storage Access Framework, camera, and sharesheet inputs converge on one import-preview path. Picker and share callbacks accept at most 20 `content://` URIs; no broad storage or camera permission is declared. Camera capture is delegated to the system camera through a narrowly configured `FileProvider` cache path. Pending shared text and URIs live only in an activity-scoped in-memory coordinator, and consumed intent extras are cleared.

The preview performs a bounded inspection for a sanitized display name, declared size, canonical type, magic bytes, and cheap media metadata. Inspection is informative rather than authoritative because a hostile provider can change between reads. Confirmation reserves deterministic confined paths and writes a durable cleanup-journal row before `AttachmentFileManager` can produce a final internal copy:

1. Reject non-content URIs, malformed Unicode names, path separators, unsupported extensions, incompatible MIME claims, and mismatched signatures.
2. Enforce the 100 MiB limit during buffered streaming even when the provider omits or lies about size.
3. Preserve a 32 MiB free-space reserve and recheck it during unknown-length copies.
4. Calculate SHA-256 in the same pass as the confined copy and `fsync` the temporary plaintext file.
5. Fully validate the stored format. Text must be valid UTF-8; JSON must parse; images/PDFs must expose valid metadata; supported Office/OpenDocument ZIP containers have bounded safe entries and are fully decompressed to verify integrity.
6. Generate a sampled, orientation-correct thumbnail off-main with at most two concurrent generators. Originals are never decoded at full resolution for list display.
7. Encrypt the thumbnail and original independently into version-1 AES-256-GCM envelopes with provider-generated nonces and attachment/purpose-bound AAD; `fsync` and atomically place each ciphertext before deleting its plaintext temporary.
8. In one Room transaction, deduplicate the checksum within the parent item, insert format-1 attachment metadata, clear the matching cleanup intent, increment the item revision, refresh attachment filename search text, and enqueue both attachment-upload and item-upsert intent.
9. Request the coalesced scheduler only after commit. Cancellation or an uncertain transaction result checks live path references before deleting anything; an uncommitted final file remains journaled for bounded retry.

Attachment deletion applies the inverse protocol. The metadata transaction first records relative ciphertext paths in the cleanup journal, then deletes metadata, updates the parent/search aggregate, and writes sync intent. File deletion happens after commit. A crash or filesystem failure therefore leaves durable, non-sensitive retry state. Reconciliation processes at most 64 journal rows per pass, rechecks Room references, never deletes a live attachment, and removes aged `.pending-*` files. It runs off-main after unlock or from an attachment surface, not during cold startup or row binding.

Thumbnails are stored separately as encrypted thumbnail-sized payloads and passed to Coil through the secure content provider. The Coil loader is manually constructed and lazy; no network fetcher or startup initializer is added, and its memory cache is cleared on lock. Opening an attachment upgrades legacy storage if necessary and returns a secure content URI. Explicit external open/share actions issue a random attachment-bound, five-minute, eight-read token and a narrow read grant. The provider exposes validated metadata and authenticates/decrypts into a private cache file before returning a seekable read-only descriptor, then immediately unlinks the filename. “Save a copy” streams verified content directly to a user-selected SAF document and removes or truncates partial output after failure. `FileProvider` remains restricted to system-camera cache capture. Room and UI models never contain attachment bytes or raw file paths.

Format version `0` remains only as a legacy migration input. After unlock, maintenance checks the stored original SHA-256 and rewrites at most eight rows per batch. An already rewritten envelope is authenticated and resumed rather than encrypted twice. Room changes to format `1` only after the original and optional thumbnail both authenticate. The byte-level contract and failure behavior are in [Encryption format](encryption-format.md).

## Room model

Persisted identifiers are collision-resistant locally generated strings, timestamps are UTC epoch milliseconds, and persisted enum-like values use stable string codes rather than ordinals. Large attachment bytes never belong in Room.

The version 3 schema separates these responsibilities:

| Table | Responsibility |
| --- | --- |
| `vault_items` | Note/item content, flags, timestamps, local and remote revisions, sync state, deletion tombstone, and conflict origin. |
| `attachments` | Attachment metadata, image/PDF dimensions, versioned app-internal relative paths, and no binary payload. |
| `attachment_file_cleanup_journal` | Durable, non-sensitive relative-path cleanup intent that survives metadata deletion and process death. |
| `tags` | Display and normalized tag identity. |
| `item_tag_cross_refs` | Many-to-many membership with a composite key and cascading foreign keys. |
| `search_documents` | One denormalized searchable aggregate per vault item. |
| `search_fts` | Room FTS4 external-content index over the aggregate. |
| `sync_operations` | Durable, deduplicated mutation intent and retry/lease metadata. |
| `sync_state` | Cursor and non-sensitive global synchronization status. |
| `app_settings` | Non-secret persisted settings behind typed access. |

Indexes cover created/updated ordering, deletion, sync state, pinned/favorite list access, attachment parent/checksum lookup, normalized tag names, cross-reference reverse lookup, and runnable sync operations. Boolean flags are paired with useful sort/filter columns where practical rather than relying only on low-selectivity single-column indexes.

Foreign keys cascade from an item to its attachment metadata, item/tag memberships, and search document. Conflict-origin identity and sync operations deliberately do not require a live parent: diagnostic copies and deletion tombstones may need to outlive the original row. Hard deletion is not part of the normal item delete action.

Schema version 2 adds nullable image width, image height, and PDF page count columns plus the cleanup-journal table and its age index. Version 3 adds a non-null stable item color with `DEFAULT` as the migration value for existing rows. `MIGRATION_1_2` and `MIGRATION_2_3` form the sole production chain. No destructive fallback exists.

### FTS aggregate

The public item ID is a UUID-like string, while FTS external content requires an integer `rowid`. It also needs text assembled from multiple tables. A regular `search_documents` row therefore owns the integer key and combines:

- title;
- body;
- normalized/display tag text;
- attachment filenames;
- OCR text.

`search_fts` indexes the corresponding text columns with Room FTS4 and the `unicode61` tokenizer. Room-managed external-content triggers keep the index aligned when the aggregate row changes. Application code writes `search_documents`, never the FTS virtual table directly. A search query joins FTS to the aggregate by `rowid`, then to `vault_items` by item ID, so results retain the canonical domain identity and deletion/archive rules.

The search screen accepts at most 200 Unicode code points, extracts at most eight bounded letter/number terms, and compiles only quoted prefix expressions. Raw FTS operators are never accepted. Input is debounced for 120 ms from the first character and switched with `flatMapLatest`, so every typed letter progressively narrows the live Room results. Results are limited to 100 narrow rows and contain only a title and bounded highlighted snippet. Deleted items are excluded while archived items remain discoverable. Attachment filenames are included in the same aggregate and covered by integration tests. `unicode61` does not provide arbitrary mid-token substring search or sophisticated Thai/CJK segmentation; adding either requires a measured compatible index strategy.

### OCR pipeline

Image and PDF imports begin in a durable `PENDING` state. After unlock and first display, the activity processes two attachments at a time through `OcrRepository`; opening a pending attachment can also request that single item. ML Kit's automatic initialization provider is removed from the merged manifest; the bundled Latin engine is initialized and constructed only on its first OCR request. Images are sampled to at most a 2048-pixel edge and roughly four million pixels. PDFs are rendered sequentially, one page and bitmap at a time, with a 50-page limit. Extracted text is control-character filtered and bounded to 200,000 characters.

Before recognition, the authenticated attachment envelope is streamed into a random app-cache lease. Cancellation deletes the lease; process death leaves only an app-private `ocr-*.tmp` file that the next pipeline pass removes after one hour. Flash storage cannot promise physical secure erasure, so this is logical deletion, not a claim of forensic wiping. App lock cancellation stops the pipeline and content access is rechecked around decryption.

Room conditionally claims `PENDING` or stale `PROCESSING` work by attachment ID and checksum. Success records the source checksum and extracted text, then recomputes the parent aggregate and updates `vault_items` plus `search_documents` in one transaction. A completed unchanged checksum is never selected again. Failures use stable non-sensitive codes; transient engine/memory failures may be explicitly retried, while corrupted, unsupported, or over-limit content is not retried indefinitely. OCR is derived local data and does not increment the user-content revision.

The FTS table duplicates searchable plaintext. This remains a deliberate known Phase 5 security limitation, not encryption.

## Transaction and revision model

Repository methods expose domain models and commands rather than Room entities. A successful content or metadata mutation:

1. reads/updates only the intended fields;
2. increments `local_revision` and records the update time;
3. marks the item as pending synchronization;
4. refreshes the search aggregate if searchable content changed;
5. inserts or updates the durable sync operation under a deterministic deduplication key;
6. commits once; and
7. requests a coalesced scheduling wake-up.

Queue coalescing does not use SQLite `REPLACE`, because replacement is implemented as delete plus insert and can invalidate ownership of in-flight work. The queue inserts-if-absent and updates the deduplication slot. A worker claims ready work with a random ten-minute lease; expired leases recover as retryable work. A newer local revision rotates the operation identity. Completion deletes only the claimed identity, advances acknowledged remote metadata conditionally, and cannot erase or mark a newer edit synchronized.

`WorkManagerSyncScheduler` enqueues one unique immediate job and one unique six-hour periodic job. Both require connected networking; periodic work also requires battery-not-low. Its automatic startup initializer is removed, and the scheduler is first touched after the first unlocked frame. Attachment operations stream and verify ciphertext before the parent item may reference a remote path. Transient failures use bounded exponential backoff; authentication and permanent validation failures do not loop indefinitely.

The in-memory fake implements `SyncApi`, `AuthProvider`, and `RemoteFileStore`. It assigns server revisions/version tokens, honors operation idempotency, emits incremental change pages, hashes streamed ciphertext, and retains no attachment bytes. Its state resets with the process, so it is deliberately not a backup or multi-device service. The stable client contract is documented in [Sync protocol](sync-protocol.md).

Concurrent content uses local revision, last-synchronized revision, server revision, and opaque version token—not device time. A mismatch marks the local version as conflict and creates a linked remote copy. A remote deletion with local edits preserves the local content. The Conflicts screen labels both versions and queues only the version explicitly selected by the user. Sync status exposes pending, running, retry, permanent-failure, conflict, and last-success state without private payloads.

### Soft deletion

Deleting sets `deleted_at`, advances the local revision, and enqueues a delete/tombstone operation without removing note content, tags, or search source data. Normal list queries exclude deleted rows. Restoring clears the deletion timestamp, advances the revision, and coalesces the pending operation back to an upsert. This preserves recoverability and gives future synchronization enough state to communicate deletion safely. Permanent deletion must wait for an acknowledged tombstone and a defined retention policy in a later phase.

## Failure and cancellation

Repository failures cross the presentation boundary as typed application failures or sealed results; views receive a non-sensitive message and no database/stack details. Cancellation is rethrown and never translated into a generic failure. A failed transaction commits none of its item, search, or queue changes. Attachment cleanup runs in a non-cancellable reconciliation section after checking Room references, while the durable journal covers process termination that no coroutine handler can observe. A post-commit scheduler failure does not roll back valid local data and cannot lose sync intent because that intent is already durable. UI warnings distinguish delayed sync, unavailable derived previews, and pending local file cleanup.

The UI offers retry only where meaningful. Authentication, network, quota, conflict, encryption/decryption, OCR, and policy-storage failures are typed. Backup errors remain a Phase 6 boundary.

## Startup and performance decisions

The startup path is intentionally short:

```text
process starts
  → lightweight application/container construction
  → MainActivity and lock/root containers inflate
  → local lock policy and bounded list Flow begin
  → lock or local loading frame draws
  → unlocked optional maintenance begins after display
```

There is no splash delay and no initial network dependency. Policy loading is a single indexed settings lookup; the initial list query is bounded and returns row projections. Updates flow incrementally from Room to the adapter. File inspection, cleanup, hashing, thumbnail generation, Coil creation, OCR, legacy encryption, backup, and cloud work remain outside the cold-start path. New-file encryption runs only during an explicit import.

The merged manifest retains only the profile installer’s AndroidX Startup entry; unused EmojiCompat and process-lifecycle initializers are explicitly removed, as is Room’s unused multi-process invalidation service. Any later library that adds a `ContentProvider` must receive the same audit. Performance claims must eventually be measured in release-like builds by the deferred baseline-profile and benchmark modules; debug timing is not an acceptance measurement.

## Security boundary and known Phase 5 risks

Phase 5 retains attachment confidentiality/integrity and a local access gate, but it is not the final VaultNote confidentiality model.

Mitigations already enforced by the architecture include:

- application-private database/files only;
- no broad storage permission or `MANAGE_EXTERNAL_STORAGE`;
- no production backend, credentials, or hardcoded secret;
- no sensitive payload in sync queue diagnostics;
- no sensitive content in expected logs;
- local edits and deletion tombstones are durable before any future remote action;
- untrusted imports are bounded, signature-checked, path-confined, copied atomically, and never executed by VaultNote;
- new attachments and thumbnails use versioned Keystore-backed AES-256-GCM envelopes with authenticated record/purpose context;
- authentication is verified before any plaintext is streamed;
- legacy plaintext is checksum-verified and upgraded atomically in bounded resumable batches;
- optional biometric/device-credential lock gates UI and secure provider access;
- recent-apps content is hidden and screenshot capture is blocked according to policy/platform capability;
- external open/share requires an explicit action, bounded in-memory token, and narrow temporary URI permission; saving requires an explicit SAF destination.

Known residual risks include:

- title, body, tags, attachment filenames, extracted OCR, and FTS text are plaintext in Room;
- an unlocked device, root access, OS compromise, debug backup/extraction, or a compromised app process can expose local data;
- app lock defaults off, and its process-local session is not a per-read Keystore authentication requirement;
- an explicitly chosen external viewer, share recipient, or save destination receives plaintext and may retain it;
- key loss or app-data clearing makes attachments unrecoverable because encrypted backup is not implemented;
- the in-memory fake backend provides neither remote backup nor multi-device durability;
- note/title/tag/OCR metadata is not end-to-end encrypted for a future production backend;
- no encrypted manual backup or restore validation exists yet;
- no server revision or conflict-resolution implementation has been exercised against a real backend.

Accordingly, Phase 5 should not be represented as whole-vault encryption or the sole copy of irreplaceable material. The detailed boundaries are documented in [Security model](security-model.md), [Encryption format](encryption-format.md), [Sync protocol](sync-protocol.md), and [Threat model](threat-model.md).

## Testing strategy

JVM tests use injected clocks, IDs, dispatchers, file managers, and fakes to make repository revisions, queue coalescing, autosave, and attachment failure cleanup deterministic. Important assertions include:

- local note creation and observation;
- content no-op behavior and revision increments;
- pin/favorite/archive commands changing only their target field;
- soft delete and restore visibility/tombstone behavior;
- search aggregate refresh with content and tags;
- atomic persistent queue creation and deduplication;
- repeated edits leaving the latest operation pending;
- autosave debounce, serialized writes, lifecycle flush, and newer-generation safety;
- error-state mapping and coroutine cancellation propagation.
- filename normalization and vault-path confinement;
- signature/MIME/extension agreement, UTF-8 validation, and bounded container decompression validation;
- streaming size limits, SHA-256, low-space behavior, and temporary-file cleanup;
- same-parent attachment deduplication, parent revision updates, search filename refresh, and sync-operation creation.
- cleanup-journal retention and retry after file deletion failure, plus proof that reconciliation preserves live referenced paths;
- camera capture recovery from an opaque saved identifier without persisting its path or URI.
- AES-GCM nonce uniqueness, round trip, context binding, corruption rejection with zero plaintext output, key-version rotation, and missing-key behavior;
- legacy checksum validation and metadata update only after successful encryption;
- fail-closed lock startup, monotonic background timeouts, and rotation-safe foreground handling;
- one-use, attachment-bound, expiring external viewer grants.

Room exports schema JSON to a version-controlled directory and supplies it to instrumentation tests. The migration test creates a representative version 1 fixture, executes the complete `1 → 2 → 3` chain, and verifies that original note/attachment data remains, new media metadata defaults to null, and item color defaults to `DEFAULT`. Destructive migration fallback is prohibited.

The standard verification commands and required SDK/JDK versions are listed in the repository [README](../README.md). Their presence documents the expected checks; only command output from the current environment can establish whether a particular run passed.

## Evolution seams

The Phase 5 boundaries are designed for replacement rather than rewrites:

- the in-memory fake backend can be replaced behind `SyncApi`, `AuthProvider`, and `RemoteFileStore` while WorkManager and the durable queue remain authoritative;
- remote API, authentication, and file storage implementations sit behind interfaces and write successful results back to Room;
- versioned Keystore aliases and envelopes permit future key rotation without placing bytes in Room;
- an OCR processor updates the existing aggregate asynchronously and only for a new checksum;
- the lock manager remains an ephemeral access boundary rather than user-data storage;
- backup/export reads a stable repository snapshot and encrypted files through Storage Access Framework;
- `:baselineprofile` and `:benchmark` modules measure cold start, scrolling, search, and note opening against a release-like build.

Any future feature that bypasses the Room source of truth, places sensitive binary content in the database, performs optional initialization before first display, or marks synchronization complete without all required remote operations violates this architecture.
