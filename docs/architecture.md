# VaultNote Phase 1 architecture

## Scope and quality boundary

Phase 1 establishes the smallest complete offline note application on which the remaining VaultNote features can safely build. It includes the UI, persistence model, repository transactions, search-index storage, persistent sync intent, and test seams. It does not simulate security or cloud behavior that is not implemented: the sync scheduler is fake and non-networking, and Room content is not encrypted at rest.

The current build has one `:app` module. That keeps startup, ownership, and build configuration straightforward while the product surface is small. Baseline Profile and Macrobenchmark modules are deferred to the performance phase, after the measured journeys and release behavior are stable enough to make those profiles meaningful.

## Architectural rules

The principal data path is:

```text
MainActivity
  └── Fragment + XML/View Binding
        └── ViewModel (immutable UI state)
              └── VaultRepository
                    └── Room database transaction
                          ├── vault and tag tables
                          ├── search aggregate and FTS index
                          └── persistent sync operation
                                └── SyncScheduler
                                      └── fake, coalesced wake-up in Phase 1
```

These rules are invariants, not conventions:

1. Room is the local source of truth. Screens collect `Flow` values derived from Room; a remote response will never be rendered directly.
2. Every user mutation is committed locally before sync is requested.
3. Related state changes are atomic. An item mutation, local revision increment, sync status, search aggregate refresh when needed, and queue coalescing share one Room transaction.
4. Scheduling happens only after the transaction commits. Scheduling failure leaves the durable operation available for recovery.
5. Main-thread work is limited to state reduction and view rendering. Database and future file/network operations use explicit injected dispatchers.
6. Lists are bounded and project only what the row needs. Phase 1 does not load all notes, attachments, or binary data into memory.
7. The fake scheduler never consumes or completes a sync operation. It only coalesces the signal that durable work exists.

## Application and dependency lifetime

`VaultNoteApplication` owns a lightweight manual dependency container. The container creates application-scoped dependencies such as the Room database, DAOs, repository, clock/ID facilities, dispatchers, and sync scheduler without retaining an `Activity`, `Fragment`, binding, or View.

Heavy or optional facilities are exposed lazily. `Application.onCreate()` must not perform a database cleanup, FTS rebuild, file hash, network request, authentication refresh, OCR initialization, image-loader initialization, or backup check. Room itself may open when the first repository query requires it; no migration work is attached to scrolling or binding a list row.

ViewModels receive their dependencies through an explicit factory. This keeps construction visible and permits deterministic fake clocks, IDs, dispatchers, and schedulers in tests without a reflection-based dependency-injection framework.

## Navigation and presentation

The app is a single `MainActivity` with one fragment container. Phase 1 uses `FragmentManager` directly rather than adding Navigation Component startup and generated-code cost for two destinations. The vault list is the root fragment; opening or creating a note replaces it with the editor and adds that transaction to the back stack. Fragment arguments restore editor identity. The list stores only its small section/page coordinates in saved state; note text is deliberately excluded from system-managed saved-state bundles. A retained editor ViewModel covers rotation, and `onStop` flushes the current draft to Room before normal background process recreation.

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

## Room model

Persisted identifiers are collision-resistant locally generated strings, timestamps are UTC epoch milliseconds, and persisted enum-like values use stable string codes rather than ordinals. Large attachment bytes never belong in Room.

The version 1 schema separates these responsibilities:

| Table | Responsibility |
| --- | --- |
| `vault_items` | Note/item content, flags, timestamps, local and remote revisions, sync state, deletion tombstone, and conflict origin. |
| `attachments` | Attachment metadata and app-internal relative paths only; no binary payload. |
| `tags` | Display and normalized tag identity. |
| `item_tag_cross_refs` | Many-to-many membership with a composite key and cascading foreign keys. |
| `search_documents` | One denormalized searchable aggregate per vault item. |
| `search_fts` | Room FTS4 external-content index over the aggregate. |
| `sync_operations` | Durable, deduplicated mutation intent and retry/lease metadata. |
| `sync_state` | Cursor and non-sensitive global synchronization status. |
| `app_settings` | Non-secret persisted settings behind typed access. |

Indexes cover created/updated ordering, deletion, sync state, pinned/favorite list access, attachment parent/checksum lookup, normalized tag names, cross-reference reverse lookup, and runnable sync operations. Boolean flags are paired with useful sort/filter columns where practical rather than relying only on low-selectivity single-column indexes.

Foreign keys cascade from an item to its attachment metadata, item/tag memberships, and search document. Conflict-origin identity and sync operations deliberately do not require a live parent: diagnostic copies and deletion tombstones may need to outlive the original row. Hard deletion is not part of the normal Phase 1 delete action.

### FTS aggregate

The public item ID is a UUID-like string, while FTS external content requires an integer `rowid`. It also needs text assembled from multiple tables. A regular `search_documents` row therefore owns the integer key and combines:

- title;
- body;
- normalized/display tag text;
- attachment filenames;
- OCR text.

`search_fts` indexes the corresponding text columns with Room FTS4 and the `unicode61` tokenizer. Room-managed external-content triggers keep the index aligned when the aggregate row changes. Application code writes `search_documents`, never the FTS virtual table directly. A search query joins FTS to the aggregate by `rowid`, then to `vault_items` by item ID, so results retain the canonical domain identity and deletion/archive rules.

Phase 1 creates and maintains this storage contract but does not expose the Phase 4 search UI. Before that UI accepts arbitrary input, it must tokenize, bound, and quote user text rather than passing raw FTS operator syntax. `unicode61` also does not provide sophisticated Thai or CJK word segmentation; improving language-specific search requires a measured, compatible tokenizer strategy.

The FTS table duplicates searchable plaintext. This is a deliberate known Phase 1 security limitation, not encryption.

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

The Phase 1 fake implementation records scheduling demand and exposes pending state for tests. It has no API, credential, or file-store dependency, performs no network I/O, and leaves operations pending. A future WorkManager implementation can replace the scheduler behind the same boundary without changing UI or repository data flow.

### Soft deletion

Deleting sets `deleted_at`, advances the local revision, and enqueues a delete/tombstone operation without removing note content, tags, or search source data. Normal list queries exclude deleted rows. Restoring clears the deletion timestamp, advances the revision, and coalesces the pending operation back to an upsert. This preserves recoverability and gives future synchronization enough state to communicate deletion safely. Permanent deletion must wait for an acknowledged tombstone and a defined retention policy in a later phase.

## Failure and cancellation

Repository failures cross the presentation boundary as typed application failures or sealed results; views receive a non-sensitive message and no database/stack details. Cancellation is rethrown and never translated into a generic failure. A failed transaction commits none of its item, search, or queue changes. A post-commit scheduler failure does not roll back valid local data and cannot lose sync intent because that intent is already durable.

The Phase 1 UI should only offer retry for transient local operations. Authentication, network, quota, encryption, and backup errors are modeled in later phases when those systems exist; they are not fabricated by the fake scheduler.

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

There is no splash delay and no initial network dependency. The initial list query is bounded, returns list-row data rather than full attachment objects, and uses indexed ordering. Updates flow incrementally from Room to the adapter. Optional image, OCR, encryption, backup, and cloud systems do not exist in the Phase 1 startup graph and must remain lazy when added.

The merged manifest retains only the profile installer’s AndroidX Startup entry; unused EmojiCompat and process-lifecycle initializers are explicitly removed, as is Room’s unused multi-process invalidation service. Any later library that adds a `ContentProvider` must receive the same audit. Performance claims must eventually be measured in release-like builds by the deferred baseline-profile and benchmark modules; debug timing is not an acceptance measurement.

## Security boundary and known Phase 1 risks

Phase 1 provides data integrity at the application transaction boundary, not the final VaultNote confidentiality model.

Mitigations already enforced by the architecture include:

- application-private database/files only;
- no broad storage permission or `MANAGE_EXTERNAL_STORAGE`;
- no production backend, credentials, or hardcoded secret;
- no sensitive payload in sync queue diagnostics;
- no sensitive content in expected logs;
- local edits and deletion tombstones are durable before any future remote action;
- no untrusted attachment parsing is exposed in this phase.

Known residual risks include:

- title, body, tags, and FTS text are plaintext in Room;
- an unlocked device, root access, OS compromise, debug backup/extraction, or a compromised app process can expose local data;
- no biometric/device-credential gate, automatic lock, screenshot blocking, or recent-apps concealment exists yet;
- no encrypted attachment store, Android Keystore key envelope, rotation, or authenticated decryption exists yet;
- the fake scheduler provides neither remote backup nor multi-device durability;
- no encrypted manual backup or restore validation exists yet;
- no server revision or conflict-resolution implementation has been exercised against a real backend.

Accordingly, Phase 1 should not be represented as a completed secure vault or used as the sole storage location for irreplaceable secrets. Later phases must add security-sensitive behavior with format documentation, corruption tests, key-loss handling, and a full threat model.

## Testing strategy

JVM tests use injected clocks, IDs, dispatchers, and fakes to make repository revisions, queue coalescing, and the 400-millisecond autosave schedule deterministic. Important Phase 1 assertions include:

- local note creation and observation;
- content no-op behavior and revision increments;
- pin/favorite/archive commands changing only their target field;
- soft delete and restore visibility/tombstone behavior;
- search aggregate refresh with content and tags;
- atomic persistent queue creation and deduplication;
- repeated edits leaving the latest operation pending;
- autosave debounce, serialized writes, lifecycle flush, and newer-generation safety;
- error-state mapping and coroutine cancellation propagation.

Room exports schema JSON to a version-controlled directory and supplies it to instrumentation tests. Version 1 intentionally has no meaningless `1 → 2` migration. Current-schema tests can validate that Room opens with foreign keys, indexes, and FTS support. At the first schema change, migration tests must create a representative version 1 database containing active, archived, deleted, tagged, conflicted, and queued records; run the direct and full migration chains; and verify data, revisions, tombstones, foreign keys, indexes, queue ownership, and search results. Destructive migration fallback is prohibited.

The standard verification commands and required SDK/JDK versions are listed in the repository [README](../README.md). Their presence documents the expected checks; only command output from the current environment can establish whether a particular run passed.

## Evolution seams

The Phase 1 boundaries are designed for replacement rather than rewrites:

- the fake scheduler becomes a unique WorkManager chain while the durable queue remains authoritative;
- remote API, authentication, and file storage implementations sit behind interfaces and write successful results back to Room;
- attachment metadata gains an app-private encrypted file store without placing bytes in Room;
- an OCR processor updates the existing aggregate asynchronously and only for a new checksum;
- lock policy wraps activity visibility without becoming a repository concern;
- backup/export reads a stable repository snapshot and encrypted files through Storage Access Framework;
- `:baselineprofile` and `:benchmark` modules measure cold start, scrolling, search, and note opening against a release-like build.

Any future feature that bypasses the Room source of truth, places sensitive binary content in the database, performs optional initialization before first display, or marks synchronization complete without all required remote operations violates this architecture.
