# VaultNote synchronization protocol

## Status and compatibility

This document defines the client-facing protocol boundary implemented in Phase 5. The Android app currently connects it to an in-memory fake backend; that fake is for deterministic development and is not a remote backup. A production service or future PC client must implement the same revision, idempotency, attachment-ordering, pagination, and error rules.

Protocol implementations negotiate an integer protocol version. Version `1` is described here. Unknown required fields or a higher incompatible protocol version must fail as `unsupported_protocol`; clients must not guess at semantics.

## Authentication

Production clients obtain an access token from an external `AuthProvider`. Tokens never enter item payloads, filenames, logs, Room, or backup archives. HTTP implementations send `Authorization: Bearer <token>` over TLS and refresh credentials outside a sync transaction.

An expired or revoked credential returns `authentication_expired`. The current work attempt stops and is not retried indefinitely. Durable local operations remain available; a successful reauthentication explicitly schedules sync again. The Phase 5 fake reports only `authenticated` or `expired` and stores no credential.

## Item schema

Item IDs and attachment IDs are collision-resistant client-generated strings. Persisted enum values are stable uppercase codes. Timestamps are UTC epoch milliseconds for display/audit only; they do not decide conflicts.

```json
{
  "protocolVersion": 1,
  "id": "uuid",
  "type": "NOTE",
  "title": "text",
  "body": "text",
  "ocrText": "derived text",
  "color": "DEFAULT|RED|ORANGE|YELLOW|GREEN|BLUE|PURPLE",
  "isPinned": false,
  "isFavorite": false,
  "isArchived": false,
  "createdAt": 0,
  "updatedAt": 0,
  "clientRevision": 1,
  "tags": ["tag"],
  "attachments": [
    {
      "id": "uuid",
      "remotePath": "opaque server path",
      "mimeType": "application/pdf",
      "fileSizeBytes": 123,
      "plaintextSha256": "64 lowercase hex characters",
      "encryptionFormatVersion": 1
    }
  ]
}
```

Responses add a monotonic server-issued `serverRevision` and opaque `versionToken`. Clients persist both. Unknown optional fields are ignored; missing required fields, invalid bounds, unsafe IDs, and unsupported enum values are permanent validation errors.

Phase 5 sends note metadata as application payload and encrypts attachment bytes. This is not a zero-knowledge metadata protocol: a production backend can see note/title/tag/OCR fields unless a later protocol version adds independently designed metadata encryption and searchable-encryption behavior.

## Idempotency and item mutations

Every mutation includes the durable Room `operationId` as its idempotency key. Repeating an operation ID must return the original outcome and must not create another server revision.

An upsert or deletion includes `expectedVersionToken`, which is the last server version observed by that client. The server applies the mutation only when the token matches the current version. A new item uses `null`. Success atomically creates a server revision and opaque replacement token. Mismatch returns `conflict` plus the current remote item, or a deletion marker if the item no longer exists.

The client marks an item synchronized only after the response is durable locally and no newer local revision or required attachment operation remains. Completion from a stale leased operation may update acknowledged remote-version metadata, but it cannot erase a newer queue identity or mark the newer content synchronized.

## Attachment upload

Attachment bytes use the documented [attachment encryption envelope](encryption-format.md). Upload order is mandatory:

1. Validate, checksum, encrypt, fsync, and atomically store the file locally.
2. Commit attachment metadata and a deduplicated `UPLOAD_ATTACHMENT` operation in Room.
3. Stream ciphertext to the remote file store with operation ID, attachment ID, and plaintext SHA-256 metadata.
4. Resume or repeat using the same operation ID after interruption.
5. Verify remote completion and checksum metadata.
6. Persist the opaque remote path and `UPLOADED` state locally.
7. Upload item metadata referencing only verified remote paths.
8. Mark the item synchronized in a Room transaction only when all current required operations are complete.

The server must never interpret a client filename as a storage path. Remote paths are opaque server-generated values. A partial transfer is not visible as complete metadata; retrying the same operation is idempotent. Phase 5's fake streams and hashes ciphertext but intentionally retains no attachment bytes.

## Incremental download and pagination

Clients call `pullChanges(cursor, limit)`. The cursor is opaque outside the issuing server; the fake uses a decimal server revision only for deterministic tests. A response contains ordered changes, `nextCursor`, and `hasMore`.

Clients validate and commit one page plus its cursor in the same Room transaction. A crash before commit replays the page safely. Pages are bounded to 200 records; Android requests 100 and processes at most four pages per worker run before yielding. Newer work is rescheduled rather than retaining an unbounded response in memory.

An upsert contains the complete current item metadata. A deletion contains item ID, server revision, and version token. The UI continues to observe Room; downloaded API objects are never rendered directly.

## Deletion tombstones

Local deletion first sets `deletedAt` and queues `DELETE_ITEM`; it does not hard-delete note content. The server records a versioned tombstone. Incremental feeds retain tombstones long enough for every supported client retention window. A client acknowledges the tombstone by advancing its cursor.

If the local item has no edits after its last synchronized revision, a downloaded deletion moves it to local Trash. If local content changed concurrently, the app retains that content as a surfaced conflict and never silently discards it. Permanent local purge requires a separately specified retention policy and acknowledged tombstone; it is not part of Phase 5.

## Conflict rules

Device timestamps are never the sole conflict signal. Clients compare local revision, last-synchronized local revision, remote revision, expected version token, and the current server version.

- Identical server token: no change.
- No local edits since `lastSyncedRevision`: accept the remote version.
- Independent merge-safe fields may be merged by a future resolver with field-level base metadata.
- Concurrent title/body/OCR content: preserve the local item and create a remote conflict copy linked by `conflictOriginId`.
- Remote deletion plus local edits: preserve the edited local item in Conflicts.
- The user selects a version explicitly. Resolution removes diagnostic copies, advances local revision, adopts the current remote token, and queues a new upsert.

Conflict copies are local diagnostic data and are not automatically uploaded as unrelated items.

## Error model

Errors contain a stable code, retryability classification, optional bounded retry-after duration, and non-sensitive correlation ID. They never include note content, filenames, paths, tokens, keys, ciphertext, or raw private server responses.

| Code | Retry | Meaning |
| --- | --- | --- |
| `network_unavailable` | yes | No usable network path. |
| `server_unavailable` | yes | Transient server or transfer failure. |
| `authentication_expired` | no automatic loop | User/session reauthentication required. |
| `invalid_request` | no | Payload or protocol validation failed. |
| `quota_exceeded` | no until user action | Remote capacity must be changed. |
| `not_found` | no, unless operation is idempotent delete | Referenced remote object is absent. |
| `corrupted_upload` | no | Verification failed; local ciphertext remains untouched. |
| `conflict` | user resolution | Expected version token did not match. |

Transient failures use bounded exponential backoff. Android starts at 30 seconds, caps at six hours, and converts repeated failures to attention-required state after ten attempts. Permanent failures remain visible in Sync status and are not retried indefinitely.

## Scheduling and leases

Android schedules unique immediate work and unique six-hour periodic work with a connected-network constraint; periodic work also requires battery-not-low. WorkManager initialization is on demand after first display. Queue rows remain authoritative—WorkManager acceptance is not proof of upload.

A worker claims a row with a random lease and expiry. Expired `RUNNING` rows return to retry state after process death or interruption. A newer local edit rotates the operation identity in the same deduplication slot, invalidating stale ownership. All remote operations remain idempotent across device restart and duplicate delivery.
