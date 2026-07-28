# VaultNote opaque relay protocol

## Scope and versioning

Protocol version `3` is the first end-to-end-encryption-ready relay contract. The relay stores and forwards opaque client-created envelopes. It does not receive a note title, body, tag, filename, OCR text, plaintext checksum, sync password, or encryption key.

Every protected request sends:

```http
X-VaultNote-Protocol: 3
Authorization: Bearer vns_<high-entropy-token>
```

A missing or incompatible version returns HTTP `426`. A missing or invalid token returns `401`. Except for `/health`, all endpoints require both headers. Clients must use HTTPS and verify the configured TLS identity before sending the token.

Identifiers are 1–128 ASCII letters, digits, `_`, or `-`. Clients generate collision-resistant item, attachment, and operation IDs locally. The relay never treats an identifier as a filesystem path.

## Pairing and encryption keys

Relay initialization creates four independent values:

- a vault ID;
- a high-entropy relay authentication token;
- a TLS certificate and SHA-256 certificate fingerprint;
- a random 32-byte password-derivation salt.

The bearer token authorizes access to the relay but cannot decrypt vault content. The user supplies a separate sync encryption password to each client. That password is never sent to the relay.

Clients obtain the key-derivation parameters from `GET /v1/relay`. Protocol 3 uses PBKDF2-HMAC-SHA256 with 600,000 iterations, the relay-provided 32-byte salt, and a 256-bit result. Clients derive purpose-separated keys from that result with HKDF-SHA256. The labels are:

```text
VaultNote Sync v3 Item
VaultNote Sync v3 Attachment
VaultNote Sync v3 Key Check
```

The HKDF definition is exact: Extract uses HMAC-SHA256 with 32 zero bytes as salt and the
32-byte PBKDF2 result as input keying material. Expand uses the UTF-8 label above as `info`,
appends counter byte `0x01`, and takes the first 32 output bytes. No terminator is included in a
label.

Clients must clear password and intermediate key material when practical. Platform key protection may wrap a derived key for unlocked-session reuse, but clients must not upload it or store it as plaintext.

The first secure pairing transfers the base URL or discovered vault ID, authentication token, and TLS certificate fingerprint together, for example through a locally generated QR code or manual comparison. An mDNS result alone is not a trust root. After pairing, clients retain the vault ID and certificate fingerprint and may automatically rediscover a changed LAN address.

## Encryption envelope

The relay treats the envelope as opaque bytes. Client implementations use the following versioned binary layout before Base64 encoding item and key-check envelopes:

| Field | Size | Encoding |
| --- | ---: | --- |
| Magic | 4 | ASCII `VNS3` |
| Envelope version | 1 | `0x01` |
| Purpose | 1 | `0x01` item, `0x02` attachment, `0x03` key check |
| Key version | 2 | unsigned big-endian, initially `1` |
| Nonce | 12 | cryptographically random |
| Plaintext length | 8 | unsigned big-endian |
| Ciphertext and tag | variable | AES-256-GCM ciphertext followed by its 16-byte tag |

Each encryption generates a fresh 96-bit nonce. Nonces must never be reused with the same purpose key. The authenticated additional data is:

```text
"VaultNote Sync Envelope" || header-through-plaintext-length ||
length-prefixed(vaultId) || length-prefixed(objectId)
```

Lengths in additional data are unsigned 32-bit big-endian byte counts. For a key check, `objectId` is the ASCII string `key-check`. For an attachment, the binary envelope is uploaded directly. For an item or key check, the complete binary envelope is standard Base64 encoded in JSON.

The SHA-256 values sent alongside envelopes cover the complete encrypted envelope, not plaintext. Clients authenticate AES-GCM before parsing or exposing plaintext and reject a purpose, vault ID, object ID, length, key version, or tag mismatch. Decryption failure never falls back to plaintext.

An item plaintext is canonical UTF-8 JSON containing the complete current item metadata and its attachment references. Unknown optional fields are preserved where possible. A protocol fixture will accompany client integration; clients must not independently reinterpret the envelope layout.

## Relay information and key validation

`GET /v1/relay` returns protocol compatibility, vault identity, built-in TLS identity, discovery type, key-derivation settings, and server limits.

`GET /v1/key-check` returns the stored encrypted key-check envelope or `404`. The first client writes one with:

```json
{
  "encryptedKeyCheck": "<base64 envelope>",
  "ciphertextSha256": "<64 lowercase hex characters>"
}
```

`PUT /v1/key-check` returns `201` when created, `204` for an identical replay, or `409 key_check_already_initialized` for a different value. A joining client decrypts this envelope before attempting any item mutation. Wrong-password failure is local and must not leak password guesses to the relay.

## Item mutation and revision rules

An upsert uses `PUT /v1/items/{itemId}` with a durable, unique operation header:

```http
X-VaultNote-Operation-Id: <operationId>
```

```json
{
  "expectedVersionToken": null,
  "encryptedPayload": "<base64 envelope>",
  "ciphertextSha256": "<64 lowercase hex characters>"
}
```

For an existing item, `expectedVersionToken` is the last token observed by that client. A delete uses `DELETE /v1/items/{itemId}` with the same operation header and:

```json
{"expectedVersionToken":"<opaque current token>"}
```

Success atomically allocates a monotonically increasing server revision and a new opaque version token. A missing or stale expected token returns HTTP `409` with `outcome: "CONFLICT"` and the current encrypted remote item or tombstone. The relay never chooses a winning note body.

An operation ID is globally single-use. Repeating the same ID and exact logical request returns the stored result without allocating another revision, including after restart. Reusing it for another request returns `409 idempotency_key_reused`. After resolving a conflict, the client creates a new operation ID.

## Incremental changes and tombstones

`GET /v1/changes?cursor=<cursor>&limit=<1..200>` returns ascending changes:

```json
{
  "changes": [
    {
      "itemId": "item-id",
      "serverRevision": 42,
      "versionToken": "opaque-token",
      "deleted": false,
      "encryptedPayload": "<base64 envelope>",
      "ciphertextSha256": "<sha256>"
    }
  ],
  "nextCursor": "42",
  "hasMore": false
}
```

Protocol 3 cursors are decimal revisions but clients treat them as opaque strings. A client validates and commits a page and its cursor in one local transaction. Tombstones have `deleted: true` and null envelope fields and are retained indefinitely by this relay version. Device timestamps never decide a conflict.

Each item envelope is a complete replacement snapshot, not a character-level patch. “Progressive sync” means incremental server revisions and attachment transfers: only changed items and missing attachments move. Concurrent content edits are preserved as conflict copies by clients.

## Attachments

Clients encrypt an attachment as a stream, hash the complete encrypted envelope, fsync it locally, then upload:

```http
PUT /v1/attachments/{attachmentId}
X-VaultNote-Operation-Id: <operationId>
X-VaultNote-Ciphertext-SHA256: <sha256>
Content-Length: <encrypted bytes>
Content-Type: application/octet-stream
```

The relay streams to a private temporary file, enforces the declared and maximum size, verifies SHA-256, atomically installs the file, and records the operation. A partial or corrupted upload is never visible. Upload retry restarts the request with the same operation ID and body.

`HEAD` returns size, SHA-256, ETag, and `Accept-Ranges`. `GET` streams the envelope. A single `Range: bytes=start-end` request returns `206`, allowing interrupted downloads to resume; malformed or unsatisfiable ranges return `416`. Clients verify the full SHA-256 after reassembly and authenticate AES-GCM before use.

`DELETE` requires a new operation ID and is idempotent. Attachment identifiers are immutable: uploading different bytes under an existing identifier returns `409 attachment_id_conflict`.

An item may reference an attachment only after the client has received and persisted its successful upload receipt. Items are not marked synchronized locally until every referenced attachment and the corresponding current item revision are acknowledged.

## Errors and retry policy

Error responses contain only:

```json
{"code":"stable_code","retryable":false}
```

| HTTP | Code | Client behavior |
| ---: | --- | --- |
| 400 | `invalid_request` | Permanent until local data/request is corrected. |
| 401 | `authentication_required` | Stop automatic retry and request a valid relay token. |
| 404 | `not_found` | Validate local reference; deletes may treat absence as complete. |
| 409 | conflict-specific code | Resolve state or create a new operation; do not blind retry. |
| 413 | `payload_too_large` | Permanent for this relay limit. |
| 416 | `range_not_satisfiable` | Discard the partial download and restart safely. |
| 422 | `corrupted_upload` | Verify local encrypted file before retry. |
| 426 | `unsupported_protocol` | Upgrade the incompatible client or relay. |
| 503 | `server_unavailable` | Retry with bounded exponential backoff and jitter. |
| 500 | `internal_error` | Retry cautiously; surface repeated failure. |

The public `GET /health` endpoint returns only status and protocol version. It must not be used as proof of authentication, vault identity, or data durability.

## LAN discovery

When started with `--lan`, the relay advertises `_vaultnote-sync._tcp.local.` through mDNS/DNS-SD. TXT records contain only:

```text
protocol=3
vault=<vault ID>
tls=required
certSha256=<TLS certificate fingerprint>
```

No token, password, item count, content metadata, or filename is advertised. Paired clients select the matching vault ID and pinned fingerprint and then connect to the advertised host and port. Android clients should use `NsdManager`; desktop clients should use a native DNS-SD implementation.

Some access points and phone hotspots suppress multicast. Clients therefore keep the last successful address and offer manual host/port or pairing-QR fallback. Discovery changes reachability only; it never changes authentication or TLS trust.
