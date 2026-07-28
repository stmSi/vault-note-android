# VaultNote synchronization protocol

## Status and compatibility

This document defines the protocol-3 client implemented by Android and the opaque HTTPS relay under `sync-server/`. The older in-memory implementation remains only as a deterministic unit-test fake. The relay persists revisions, encrypted item envelopes, encrypted attachment envelopes, tombstones, idempotency receipts, and the incremental cursor; it never receives decrypted vault metadata or files.

Protocol implementations negotiate integer version `3`. The encrypted item JSON schema is independently versioned as `3`; unknown required fields or an incompatible protocol version fail as `unsupported_protocol`. The normative HTTP and binary contract is [the relay wire protocol](../../sync-server/docs/wire-protocol.md).

## Authentication

Pairing requires the relay address, bearer token, a manually compared SHA-256 TLS certificate fingerprint, and a separate shared sync password. The token is sent only after exact certificate pin validation. The password is never sent; PBKDF2-HMAC-SHA256 derives the master key and an encrypted key-check proves that clients used the same password.

Android stores the token and derived master key only inside an AES-GCM credential envelope whose non-exportable wrapping key is held by Android Keystore. It stores neither the sync password nor plaintext credentials. An expired or replaced token stops work without an automatic retry loop. Pairing the same vault again reactivates authentication-stopped queue rows and schedules unique work.

## Item schema

Item IDs and attachment IDs are collision-resistant client-generated strings. Persisted enum values are stable uppercase codes. Timestamps are UTC epoch milliseconds for display/audit only; they do not decide conflicts.

```json
{
  "schemaVersion": 3,
  "id": "uuid",
  "type": "NOTE",
  "title": "text",
  "body": "text",
  "ocrText": "derived text",
  "color": "DEFAULT|RED|ORANGE|YELLOW|GREEN|BLUE|PURPLE",
  "isPinned": false,
  "isFavorite": false,
  "isArchived": false,
  "sortPosition": 0,
  "createdAt": 0,
  "updatedAt": 0,
  "clientRevision": 1,
  "tags": ["tag"],
  "attachments": [
    {
      "id": "uuid",
      "remotePath": "/v1/attachments/uuid",
      "originalFilename": "paper.pdf",
      "mimeType": "application/pdf",
      "fileSizeBytes": 123,
      "plaintextSha256": "64 lowercase hex characters",
      "encryptionFormatVersion": 1,
      "imageWidth": null,
      "imageHeight": null,
      "pdfPageCount": 2,
      "createdAt": 0
    }
  ]
}
```

Responses add a monotonic server-issued `serverRevision` and opaque `versionToken`. Clients persist both. Unknown optional fields are ignored; missing required fields, invalid bounds, unsafe IDs, and unsupported enum values are permanent validation errors.

`sortPosition` orders items ascending inside separate pinned and unpinned groups. A drag changes the moved item's client revision and is synchronized like other metadata. Clients choose positions between adjacent items and may transactionally rebalance a crowded range; timestamps never determine an explicit manual order.

The JSON above is plaintext only inside the authenticated item envelope on paired clients. The relay sees item and attachment IDs, encrypted sizes, revisions, access timing, and tombstones, but not titles, bodies, tags, filenames, OCR text, plaintext hashes, or colors.

## Idempotency and item mutations

Every mutation includes the durable Room `operationId` as its idempotency key. Repeating an operation ID must return the original outcome and must not create another server revision. Because AES-GCM uses a random nonce, Android atomically caches the encrypted item envelope under that operation ID and reuses the exact bytes until Room commits the terminal acknowledgement. A response lost before that commit therefore remains an exact replay after process death.

An upsert or deletion includes `expectedVersionToken`, which is the last server version observed by that client. The server applies the mutation only when the token matches the current version. A new item uses `null`. Success atomically creates a server revision and opaque replacement token. Mismatch returns `conflict` plus the current remote item, or a deletion marker if the item no longer exists.

The client marks an item synchronized only after the response is durable locally and no newer local revision or required attachment operation remains. Completion from a stale leased operation may update acknowledged remote-version metadata, but it cannot erase a newer queue identity or mark the newer content synchronized.

## Attachment upload

Attachment bytes use the documented [attachment encryption envelope](encryption-format.md). Upload order is mandatory:

1. Validate, checksum, encrypt under the device Keystore key, fsync, and atomically store the file locally.
2. Commit attachment metadata and a deduplicated `UPLOAD_ATTACHMENT` operation in Room.
3. Authenticate and stream-transform the device envelope into a purpose-separated shared sync envelope without a plaintext temporary file.
4. Resume or repeat using the same operation ID after interruption.
5. Verify that relay `HEAD` returns the exact local sync-envelope SHA-256 and byte count.
6. Persist the opaque remote path and `UPLOADED` state locally.
7. Upload item metadata referencing only verified remote paths.
8. Mark the item synchronized in a Room transaction only when all current required operations are complete.

The server never receives or interprets a filename as a storage path. A partial transfer is not visible as complete metadata; retrying the same operation reuses the same local envelope. Downloads resume into an app-private ciphertext partial, verify complete SHA-256, authenticate AES-GCM in a first pass, then stream directly into a new device-bound envelope. No plaintext attachment temporary is created by synchronization.

## Incremental download and pagination

Clients call `pullChanges(cursor, limit)`. Protocol 3 uses a validated decimal revision cursor, though callers treat it as server-issued state. A response contains ordered changes, `nextCursor`, and `hasMore`.

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
| `unsupported_protocol` | no | Client and relay versions are incompatible. |
| `conflict` | user resolution | Expected version token did not match. |

Transient failures use bounded exponential backoff. Android starts at 30 seconds, caps at six hours, and converts repeated failures to attention-required state after ten attempts. Permanent failures remain visible in Sync status and are not retried indefinitely.

## Scheduling and leases

Android schedules unique immediate work and unique six-hour periodic work with a connected-network constraint; periodic work also requires battery-not-low. WorkManager initialization is on demand after first display. Queue rows remain authoritative—WorkManager acceptance is not proof of upload.

A worker claims a row with a random lease and expiry. Expired `RUNNING` rows return to retry state after process death or interruption. A newer local edit rotates the operation identity in the same deduplication slot, invalidating stale ownership. All remote operations remain idempotent across device restart and duplicate delivery.

## LAN discovery and endpoint changes

The relay advertises `_vaultnote-sync._tcp.local.` only when started with `--lan`. Android resolves it through `NsdManager`, reads protocol/vault/fingerprint TXT attributes after resolution, and keeps a multicast lock only for the bounded discovery window. Android 17 and newer requests `ACCESS_LOCAL_NETWORK` only when the user starts discovery or pairing.

Discovery is a reachability hint, not authentication. Initial pairing requires explicit fingerprint confirmation. A paired client may accept a new host/port only when discovery matches both the saved vault ID and pinned fingerprint; TLS pinning still applies to the subsequent connection. Manual address entry remains available for networks that suppress multicast.
