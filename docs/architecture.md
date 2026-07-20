# VaultNote Phase 2 architecture

## Scope and quality boundary

Phase 2 extends the complete offline note foundation with defensive attachment import, app-private file storage, derived thumbnails, sharesheet entry, and attachment viewing. It does not simulate security or cloud behavior that is not implemented: the sync scheduler remains fake and non-networking, Room content is plaintext, and attachment format version `0` is not encrypted.

The current build has one `:app` module. That keeps startup, ownership, and build configuration straightforward while the product surface is small. Baseline Profile and Macrobenchmark modules remain deferred to the performance phase, after the measured journeys and release behavior are stable enough to make those profiles meaningful.

## Architectural rules

The principal data path is:

```text
MainActivity
  └── Fragment + XML/View Binding
        └── ViewModel (immutable UI state)
              ├── VaultRepository
              └── AttachmentRepository
                    ├── AttachmentFileManager
                    │     ├── validated atomic internal file
                    │     └── bounded derived thumbnail
                    └── Room database transaction
                          ├── vault, attachment, and tag tables
                          ├── search aggregate and FTS index
                          └── persistent item/attachment operations
                                └── fake coalesced scheduler
```

These rules are invariants, not conventions:

1. Room is the local source of truth. Screens collect `Flow` values derived from Room; a remote response will never be rendered directly.
2. Every user mutation is committed locally before sync is requested.
3. Related state changes are atomic. An item mutation, local revision increment, sync status, search aggregate refresh when needed, and queue coalescing share one Room transaction.
4. Scheduling happens only after the transaction commits. Scheduling failure leaves the durable operation available for recovery.
5. Main-thread work is limited to state reduction and view rendering. Database and future file/network operations use explicit injected dispatchers.
6. Lists are bounded and project only what the row needs. Phase 2 does not load all notes, attachments, or binary data into memory.
7. The fake scheduler never consumes or completes a sync operation. It only coalesces the signal that durable work exists.
8. External filenames, MIME claims, sizes, and URIs are untrusted. Only a fully validated, checksummed internal copy may be referenced by Room.
9. RecyclerView binding resolves no content URI, hash, metadata, or thumbnail-generation work; rows receive already prepared display metadata and thumbnail files.

## Application and dependency lifetime

`VaultNoteApplication` owns a lightweight manual dependency container. The container creates application-scoped dependencies such as the Room database, DAOs, repository, clock/ID facilities, dispatchers, and sync scheduler without retaining an `Activity`, `Fragment`, binding, or View.

Heavy or optional facilities are exposed lazily. `Application.onCreate()` performs no database cleanup, FTS rebuild, file hash, network request, authentication refresh, OCR initialization, image-loader initialization, or backup check. The attachment manager, thumbnail generator, and Coil loader are first created only when an attachment surface needs them. Room itself may open when the first repository query requires it; no migration or file work is attached to scrolling or binding a list row.

ViewModels receive their dependencies through an explicit factory. This keeps construction visible and permits deterministic fake clocks, IDs, dispatchers, and schedulers in tests without a reflection-based dependency-injection framework.

## Navigation and presentation

The app is a single `MainActivity` with one fragment container and direct `FragmentManager` navigation. The vault list is the root; the note editor, import preview, and attachment viewer are shallow back-stack destinations. Fragment arguments persist only stable IDs and a non-sensitive in-memory import token. Shared text, external URIs, camera paths, and note drafts are never written into saved-state bundles. Activity- or fragment-scoped ViewModels retain pending imports across rotation; process death deliberately expires an unconfirmed external selection. Camera capture saves only an opaque, format-validated UUID in `SavedStateHandle`, allowing the app to reconstruct its own confined cache file after process recreation without persisting a path, URI, or captured bytes. `onStop` still flushes the current note draft to Room.

View Binding is scoped to the view lifecycle. A Fragment clears its binding in `onDestroyView`, and Flow collection is tied to `viewLifecycleOwner` with a started-state lifecycle gate. This prevents detached views and activities from being retained.

### Vault list

The list ViewModel exposes one immutable state with four meaningful render forms:

- loading while the first local query is unresolved;
- empty after a successful query with no matching items;
- content containing an immutable, bounded list of row models;
- error containing a safe user-facing message and a retry action only when retrying is meaningful.

The `RecyclerView` uses `ListAdapter` and `DiffUtil`. IDs derive from the persistent item identity and are stable across reordering. Content equality includes only properties displayed by the row. Each query returns a 100-row window plus one lookahead row; explicit Previous/Next controls change a bounded SQL `OFFSET` instead of accumulating the whole vault in memory. Section and page coordinates survive process recreation. Active notes order pinned items first and then recently updated items. No row binding performs database work, hashing, OCR, thumbnail generation, or attachment loading.

The toolbar switches the same shallow list UI among active Notes, Archived, and Trash sections. Archived rows can be opened or restored; trashed rows can be restored without exposing them to editing first. Soft deletion retains content and queued tombstone state, so neither archive nor trash is a one-way action.

### Note editor and autosave

Typing first updates immutable editor state in memory, so rendering is independent of database latency. The autosave controller assigns a monotonically increasing generation to edited title/body snapshots:

1. A changed snapshot replaces the pending snapshot immediately.
2. A coroutine waits for 400 milliseconds of inactivity.
3. Saves are serialized; a newer edit does not cancel an already-running Room transaction.
4. Completion only marks the generation it wrote as persisted. If a newer generation exists, that newer state remains dirty.
5. Explicit `flush()` cancels the pending delay, waits for any active save as needed, and persists the newest generation.

The editor flushes before an explicit navigation away and when its host stops, covering the application entering the background. The repository treats identical title/body content as a no-op, so a debounce followed immediately by a lifecycle flush cannot manufacture an extra local revision or duplicate queue operation. Autosave writes a persistent operation, but it does not enqueue a separate WorkManager request per keystroke; the scheduler signal is coalesced.

Pin, favorite, archive, soft-delete, and restore are explicit repository commands rather than whole-entity replacement. This prevents a stale editor snapshot from reverting an independently changed flag.

## Attachment import and viewing

Photo Picker, Storage Access Framework, camera, and sharesheet inputs converge on one import-preview path. Picker and share callbacks accept at most 20 `content://` URIs; no broad storage or camera permission is declared. Camera capture is delegated to the system camera through a narrowly configured `FileProvider` cache path. Pending shared text and URIs live only in an activity-scoped in-memory coordinator, and consumed intent extras are cleared.

The preview performs a bounded inspection for a sanitized display name, declared size, canonical type, magic bytes, and cheap media metadata. Inspection is informative rather than authoritative because a hostile provider can change between reads. Confirmation reserves deterministic confined paths and writes a durable cleanup-journal row before `AttachmentFileManager` can produce a final internal copy:

1. Reject non-content URIs, malformed Unicode names, path separators, unsupported extensions, incompatible MIME claims, and mismatched signatures.
2. Enforce the 100 MiB limit during buffered streaming even when the provider omits or lies about size.
3. Preserve a 32 MiB free-space reserve and recheck it during unknown-length copies.
4. Calculate SHA-256 in the same pass as the copy and `fsync` the temporary file.
5. Fully validate the stored format. Text must be valid UTF-8; JSON must parse; images/PDFs must expose valid metadata; supported Office/OpenDocument ZIP containers have bounded safe entries and are fully decompressed to verify integrity.
6. Generate a sampled, orientation-correct thumbnail off-main with at most two concurrent generators. Originals are never decoded at full resolution for list display.
7. Atomically rename the completed temporary file inside the app-private vault directory.
8. In one Room transaction, deduplicate the checksum within the parent item, insert attachment metadata, clear the matching cleanup intent, increment the item revision, refresh attachment filename search text, and enqueue both attachment-upload and item-upsert intent.
9. Request the coalesced scheduler only after commit. Cancellation or an uncertain transaction result checks live path references before deleting anything; an uncommitted final file remains journaled for bounded retry.

Attachment deletion applies the inverse protocol. The metadata transaction first records the relative paths in the cleanup journal, then deletes metadata, updates the parent/search aggregate, and writes sync intent. File deletion happens after commit. A crash or filesystem failure therefore leaves a durable, non-sensitive retry record rather than an undiscoverable plaintext orphan. Reconciliation processes at most 64 journal rows per pass, rechecks Room path references before deletion, never deletes a live attachment, and also removes aged `.pending-*` files. It runs off-main when an attachment surface is used, not during cold startup or row binding.

Thumbnails are stored separately and passed to Coil as local thumbnail-sized files. The Coil loader is manually constructed and lazy; no network fetcher or startup initializer is added. Opening an attachment first resolves its relative path inside the vault root. Images can render in the attachment viewer, while an explicit external-open action uses `FileProvider`, the canonical MIME type, and a temporary read grant. Room and UI models never contain attachment bytes.

Format version `0` is an explicit transitional contract, not encryption. Phase 3 must rewrite each attachment and thumbnail into a documented authenticated AES-256-GCM format before changing the version, and must retain atomic replacement and rollback behavior.

## Room model

Persisted identifiers are collision-resistant locally generated strings, timestamps are UTC epoch milliseconds, and persisted enum-like values use stable string codes rather than ordinals. Large attachment bytes never belong in Room.

The version 2 schema separates these responsibilities:

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

Schema version 2 adds nullable image width, image height, and PDF page count columns plus the cleanup-journal table and its age index. `MIGRATION_1_2` is additive, preserves every existing row, and is included in the sole production migration chain. No destructive fallback exists.

### FTS aggregate

The public item ID is a UUID-like string, while FTS external content requires an integer `rowid`. It also needs text assembled from multiple tables. A regular `search_documents` row therefore owns the integer key and combines:

- title;
- body;
- normalized/display tag text;
- attachment filenames;
- OCR text.

`search_fts` indexes the corresponding text columns with Room FTS4 and the `unicode61` tokenizer. Room-managed external-content triggers keep the index aligned when the aggregate row changes. Application code writes `search_documents`, never the FTS virtual table directly. A search query joins FTS to the aggregate by `rowid`, then to `vault_items` by item ID, so results retain the canonical domain identity and deletion/archive rules.

The foundation creates and maintains this storage contract but does not expose the Phase 4 search UI. Before that UI accepts arbitrary input, it must tokenize, bound, and quote user text rather than passing raw FTS operator syntax. `unicode61` also does not provide sophisticated Thai or CJK word segmentation; improving language-specific search requires a measured, compatible tokenizer strategy.

The FTS table duplicates searchable plaintext. This remains a deliberate known Phase 2 security limitation, not encryption.

## Transaction and revision model

Repository methods expose domain models and commands rather than Room entities. A successful content or metadata mutation:

1. reads/updates only the intended fields;
2. increments `local_revision` and records the update time;
3. marks the item as pending synchronization;
4. refreshes the search aggregate if searchable content changed;
5. inserts or updates the durable sync operation under a deterministic deduplication key;
6. commits once; and
7. requests a coalesced scheduling wake-up.

Queue coalescing must not use SQLite `REPLACE`, because replacement is implemented as delete plus insert and can invalidate ownership of in-flight work. The queue instead inserts-if-absent and updates the deduplication slot. A newer local revision replaces retry/lease ownership with a new operation identity. In later phases, completion must be conditional on the worker's claimed operation identity so an old in-flight upload cannot erase a newer edit.

The Phase 2 fake implementation records scheduling demand and exposes pending state for tests. It has no API or credential, performs no network I/O, and leaves operations pending. Attachment imports queue an `UPLOAD_ATTACHMENT` operation plus the item metadata upsert. A future WorkManager implementation must enforce attachment completion before uploading or marking referenced item metadata synchronized.

### Soft deletion

Deleting sets `deleted_at`, advances the local revision, and enqueues a delete/tombstone operation without removing note content, tags, or search source data. Normal list queries exclude deleted rows. Restoring clears the deletion timestamp, advances the revision, and coalesces the pending operation back to an upsert. This preserves recoverability and gives future synchronization enough state to communicate deletion safely. Permanent deletion must wait for an acknowledged tombstone and a defined retention policy in a later phase.

## Failure and cancellation

Repository failures cross the presentation boundary as typed application failures or sealed results; views receive a non-sensitive message and no database/stack details. Cancellation is rethrown and never translated into a generic failure. A failed transaction commits none of its item, search, or queue changes. Attachment cleanup runs in a non-cancellable reconciliation section after checking Room references, while the durable journal covers process termination that no coroutine handler can observe. A post-commit scheduler failure does not roll back valid local data and cannot lose sync intent because that intent is already durable. UI warnings distinguish delayed sync, unavailable derived previews, and pending local file cleanup.

The UI offers retry only for transient local operations. Authentication, network, quota, encryption, and backup errors are modeled in later phases when those systems exist; they are not fabricated by the fake scheduler.

## Startup and performance decisions

The startup path is intentionally short:

```text
process starts
  → lightweight application/container construction
  → MainActivity and root Fragment inflate
  → bounded local Room Flow begins
  → first loading/local frame draws
  → optional work may be considered after first display
```

There is no splash delay and no initial network dependency. The initial list query is bounded, returns list-row data rather than full attachment objects, and uses indexed ordering. Updates flow incrementally from Room to the adapter. File inspection, cleanup, hashing, thumbnail generation, Coil creation, OCR, encryption, backup, and cloud work remain outside the cold-start path.

The merged manifest retains only the profile installer’s AndroidX Startup entry; unused EmojiCompat and process-lifecycle initializers are explicitly removed, as is Room’s unused multi-process invalidation service. Any later library that adds a `ContentProvider` must receive the same audit. Performance claims must eventually be measured in release-like builds by the deferred baseline-profile and benchmark modules; debug timing is not an acceptance measurement.

## Security boundary and known Phase 2 risks

Phase 2 provides defensive import and data integrity at the application transaction boundary, not the final VaultNote confidentiality model.

Mitigations already enforced by the architecture include:

- application-private database/files only;
- no broad storage permission or `MANAGE_EXTERNAL_STORAGE`;
- no production backend, credentials, or hardcoded secret;
- no sensitive payload in sync queue diagnostics;
- no sensitive content in expected logs;
- local edits and deletion tombstones are durable before any future remote action;
- untrusted imports are bounded, signature-checked, path-confined, copied atomically, and never executed by VaultNote;
- external viewing requires an explicit action and a narrow temporary URI permission.

Known residual risks include:

- title, body, tags, and FTS text are plaintext in Room;
- an unlocked device, root access, OS compromise, debug backup/extraction, or a compromised app process can expose local data;
- no biometric/device-credential gate, automatic lock, screenshot blocking, or recent-apps concealment exists yet;
- attachment bytes and thumbnails are plaintext in app-private storage with format version `0`; no Android Keystore envelope, rotation, or authenticated decryption exists yet;
- the fake scheduler provides neither remote backup nor multi-device durability;
- no encrypted manual backup or restore validation exists yet;
- no server revision or conflict-resolution implementation has been exercised against a real backend.

Accordingly, Phase 2 should not be represented as a completed secure vault or used as the sole storage location for irreplaceable secrets. Later phases must add security-sensitive behavior with format documentation, corruption tests, key-loss handling, and a full threat model.

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

Room exports schema JSON to a version-controlled directory and supplies it to instrumentation tests. The migration test creates a representative version 1 fixture, executes `MIGRATION_1_2`, validates the complete version 2 schema, and verifies that original note/attachment data remains while new media metadata defaults to null. Destructive migration fallback is prohibited.

The standard verification commands and required SDK/JDK versions are listed in the repository [README](../README.md). Their presence documents the expected checks; only command output from the current environment can establish whether a particular run passed.

## Evolution seams

The Phase 2 boundaries are designed for replacement rather than rewrites:

- the fake scheduler becomes a unique WorkManager chain while the durable queue remains authoritative;
- remote API, authentication, and file storage implementations sit behind interfaces and write successful results back to Room;
- attachment format version `0` is atomically rewritten by a Keystore-backed encrypted file store without placing bytes in Room;
- an OCR processor updates the existing aggregate asynchronously and only for a new checksum;
- lock policy wraps activity visibility without becoming a repository concern;
- backup/export reads a stable repository snapshot and encrypted files through Storage Access Framework;
- `:baselineprofile` and `:benchmark` modules measure cold start, scrolling, search, and note opening against a release-like build.

Any future feature that bypasses the Room source of truth, places sensitive binary content in the database, performs optional initialization before first display, or marks synchronization complete without all required remote operations violates this architecture.
