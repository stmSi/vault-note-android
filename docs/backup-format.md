# VaultNote manual backup format

## Scope

VaultNote backup version `1` is a portable, password-encrypted ZIP archive with the `.vnb` extension. It contains every local vault item, including archived items, soft-deletion tombstones and conflict copies, plus tags, tag memberships, attachment metadata, OCR text and original attachment bytes.

The format is independent of the Android Keystore. During export, an attachment is authenticated and decrypted from its device-bound envelope, then immediately streamed into a password-encrypted backup entry. During restore, it is authenticated with the backup password, validated against its stored content checksum and format, and encrypted under the destination installation's current Keystore key. Keystore key bytes are never exported.

This is the primary manual recovery mechanism. Android Auto Backup remains disabled.

## Archive layout

Version `1` uses these exact ZIP entry names:

```text
manifest.json
checksums.json.enc
database.json.enc
attachments/
  00000001.bin
  00000002.bin
  ...
```

Directories are implicit and directory entries are rejected. The ZIP contains at most 100,003 entries, 100,000 attachments and 8 GiB of declared uncompressed data. Entry names are ASCII format-owned paths; absolute paths, backslashes, NULs, empty segments, `.` and `..` are rejected. Duplicate names and entries not declared by the encrypted checksum index are rejected. A per-entry compression-ratio bound limits decompression bombs. Export currently stores entries without ZIP compression because encrypted data is not usefully compressible.

`manifest.json` is the only plaintext entry. It exposes format and cryptographic parameters, archive creation time, a random archive ID, and the encrypted checksum-index size and SHA-256. It does not expose item counts, titles, tags, filenames, MIME types, attachment sizes or content checksums.

## Plaintext manifest

The manifest is strict JSON. Unknown, missing, duplicate or malformed fields are not accepted by the decoder. JSON object field order is not significant. Version `1` has this shape:

```json
{
  "magic": "VaultNoteBackup",
  "formatVersion": 1,
  "minimumReaderVersion": 1,
  "createdAtEpochMillis": 1725000000000,
  "archiveId": "base64-16-random-bytes",
  "kdf": {
    "algorithm": "PBKDF2-HMAC-SHA256",
    "iterations": 600000,
    "salt": "base64-32-random-bytes",
    "keyBits": 256
  },
  "cipher": "AES-256-GCM",
  "checksums": {
    "path": "checksums.json.enc",
    "ciphertextSize": 1234,
    "ciphertextSha256": "64-lowercase-hex-characters"
  }
}
```

Readers accept only the exact version-1 KDF and cipher parameters. The password must contain 12–128 valid Unicode code points, with no NUL or unpaired surrogate. Password characters are retained only in operation-scoped mutable arrays and are overwritten after export, validation, cancellation or ViewModel disposal. Android text widgets can still create framework-managed copies in process memory; VaultNote makes no claim of forensic erasure from managed memory.

## Key derivation and encrypted entries

PBKDF2-HMAC-SHA256 derives a 256-bit key from the UTF-8 password semantics implemented by `PBEKeySpec`, a fresh 32-byte random salt and 600,000 iterations. Every encrypted entry then uses AES-256-GCM with a fresh provider-generated 12-byte nonce and a 128-bit tag. A nonce is never accepted from archive metadata or reused intentionally.

Each encrypted entry has this binary envelope:

| Offset | Size | Field | Validation |
| ---: | ---: | --- | --- |
| 0 | 4 | Magic | ASCII `VNBE` |
| 4 | 1 | Entry format | `0x01` |
| 5 | 1 | Nonce length | exactly `12` |
| 6 | 12 | Nonce | unique random bytes |
| 18 | N | Ciphertext | streamed, followed by the 16-byte GCM tag |

GCM additional authenticated data is the complete 18-byte entry header, followed by the canonical manifest binding, followed by the UTF-8 entry path. The canonical binding contains the manifest magic, format versions, creation time, archive ID, KDF algorithm/iterations/salt/key size and cipher. It intentionally excludes the checksum-index ciphertext size and hash, which cannot be known until that entry is encrypted; those two values are instead integrity-protected by locating `manifest.json` through the format-owned exact path and using them to verify the checksum ciphertext before decryption.

Binding the path prevents a valid encrypted database or attachment entry from being substituted at another location. Decryption performs a complete authentication pass to a discard sink before a second streaming pass writes plaintext, so an invalid tag, wrong password, wrong path or modified manifest binding produces zero caller-visible plaintext.

## Encrypted checksum index

`checksums.json.enc` decrypts to a bounded streaming JSON array. Each object contains an exact encrypted-entry path, ciphertext byte count and lowercase SHA-256 of the complete encrypted envelope. It covers `database.json.enc` and every attachment entry. It does not list itself because its own ciphertext hash and size are in the plaintext manifest.

Restore verifies the checksum-index encrypted size and SHA-256 before attempting its GCM authentication. It then verifies every declared encrypted entry and requires the ZIP entry set to match the checksum index plus the manifest and checksum entry exactly. SHA-256 detects transport/archive corruption early; GCM remains the cryptographic authenticity control.

## Encrypted database snapshot

`database.json.enc` decrypts to strict, ordered, bounded JSON containing:

- item identity, type, color, title, body, OCR text, flags, timestamps, local revision, soft-deletion timestamp and conflict origin;
- tag identity, display name, normalized name and creation timestamp;
- item/tag memberships; and
- attachment identity, parent, sanitized filename, canonical MIME type, size, media dimensions, SHA-256, creation time, OCR state/text/checksum/failure metadata and its opaque archive content path.

Remote revisions, last-synchronized revisions, server tokens, remote paths, upload status, local ciphertext paths, thumbnails, FTS rows, sync operations/state and device settings are excluded. They are installation-specific or derived. Restored items and attachments become local pending changes, FTS documents are rebuilt, and durable upload/upsert operations are queued after commit.

The exporter reads each table through keyset-paged DAO queries inside one Room transaction, so metadata is a consistent snapshot without loading the whole vault into memory. Attachments are streamed separately and checked against the snapshot's plaintext size and SHA-256.

## Export protocol

1. Require an unlocked vault and validate the password.
2. Count records and attachment bytes in Room and check configured limits and app-private free space.
3. Generate a random archive ID, salt and entry nonces.
4. Build the complete archive under app-private cache using buffered streaming and `fsync`.
5. Authenticate each current attachment envelope before streaming its plaintext into the backup cipher; verify its plaintext length and SHA-256 against Room.
6. Write the encrypted checksum index and final strict manifest.
7. Only after the private archive is complete, copy it to the user-selected Storage Access Framework destination.
8. On cancellation or failure, delete the private archive and ask the document provider to delete the partial destination, falling back to truncation.

The final SAF copy cannot be universally atomic because document-provider behavior is outside VaultNote's control. A successful result means the copy completed and streams closed successfully; users should still retain an older known-good backup until the new archive has been restored or independently preserved.

## Restore and commit protocol

Restore has a validation/staging phase and a separate user-confirmed commit:

1. Copy the selected content URI into a bounded app-private staging directory.
2. Inspect every ZIP entry for count, size, compression-ratio, duplicate-name and path-safety violations.
3. Strictly decode the manifest, derive the password key and authenticate the encrypted checksum index.
4. Verify the exact entry set and every encrypted entry's size and SHA-256.
5. Authenticate the database entry before writing its plaintext to a short-lived private file; stream it into a private SQLite staging database and delete the plaintext file.
6. Validate IDs, Unicode, field lengths, enums, timestamps, normalized tags, filenames, MIME syntax, attachment limits, SHA-256 values, references and declared counts.
7. Plan collision handling without touching live Room: an existing item ID becomes a new local copy, an existing normalized tag is reused, and an existing attachment ID receives a new local ID.
8. For one attachment at a time, authenticate to a private temporary file, verify size/SHA-256/content signature/canonical MIME, encrypt to a same-filesystem pending Keystore envelope and delete plaintext.
9. Present item/attachment counts and collision-copy count for explicit confirmation.
10. Persist a file-cleanup journal, atomically place pending attachment ciphertext, then commit items, tags, relationships, attachment metadata, rebuilt FTS documents and durable sync operations in one Room transaction.
11. Schedule one coalesced sync request after commit and remove staging data.

Validation, a wrong password, cancellation, corruption or unsupported content changes no live Room rows. Commit is non-cancellable once it begins so coroutine cancellation cannot interrupt the file/transaction boundary. The cleanup journal covers process death after a restored ciphertext is placed but before metadata commit.

## Duplicate and compatibility behavior

Restore never silently overwrites an existing item or attachment ID. Item collisions create independent copies with generated IDs. Tag names use the same normalization rules as live edits and reuse an existing normalized tag. Attachment content is not silently deduplicated across different records because doing so would change ownership and deletion semantics.

Unknown format, minimum-reader, KDF, key-size, cipher, database-schema or encrypted-entry versions fail closed. Version `1` performs no best-effort field skipping. A future reader may add an explicit migration for older supported versions, but it must preserve authentication and complete staging before modifying live data.

## Residual risks

- A weak or forgotten backup password cannot be recovered by VaultNote. PBKDF2 raises offline-guessing cost but cannot make a low-entropy password strong.
- Anyone with an archive can see its creation time and cryptographic parameters and can delete, replay or withhold the archive.
- Restoring an older valid archive is allowed; there is no trusted external monotonic counter to detect rollback.
- Plaintext exists briefly in app-private staging while an authenticated database or one attachment is validated and re-encrypted. Normal completion/cancellation logically deletes it, but flash storage cannot guarantee forensic erasure.
- A rooted device, compromised OS or compromised VaultNote process can observe passwords or plaintext during an operation.
- The selected document provider controls the exported copy after it leaves VaultNote's private storage.
