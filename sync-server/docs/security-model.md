# Opaque relay security model

## Security goals

The protocol separates access control from content confidentiality:

- HTTPS authenticates the configured relay and protects traffic in transit.
- A 256-bit random bearer token restricts relay access.
- Client-side AES-256-GCM protects item metadata and attachment contents end to end.
- Server revisions, version tokens, tombstones, and idempotency records prevent accidental overwrite and duplicate application.
- Streamed I/O, bounded payloads, strict identifiers, checksums, private permissions, and atomic file installation limit malformed-input and process-interruption damage.

The relay stores only ciphertext envelopes plus operational metadata. It cannot recover note text, tags, OCR, original filenames, plaintext checksums, attachment media, or the sync password.

## Trust boundaries

Clients trust their local unlocked process, cryptographic implementation, sync password, pinned relay identity, and the user's pairing action. The relay is trusted for availability, monotonic revision assignment, and durable storage, but not for content confidentiality. The local network and mDNS advertisements are untrusted.

The relay necessarily observes:

- vault ID and opaque object/operation identifiers;
- ciphertext sizes and hashes;
- request timing, source network address, transfer volume, and revision history;
- deletion and conflict frequency.

Padding, traffic-shape hiding, anonymous access, multi-user authorization, and private-information-retrieval search are out of scope.

## Threat analysis

### Malicious local peer or spoofed discovery

An attacker can advertise a fake DNS-SD service, suppress the real advertisement, or redirect IP traffic. A previously paired client rejects a different vault ID or certificate fingerprint. First pairing must transfer the fingerprint with the bearer token through a trusted local comparison or QR flow; trusting an unverified mDNS fingerprint could disclose the token to an attacker.

The attacker can still block or delay synchronization. Manual-address fallback improves availability but does not remove certificate verification.

### Stolen relay token

The token allows reading, replacing, or deleting ciphertext and observing relay metadata. It does not decrypt content. The operator can rotate the token while the relay is stopped; all clients must then receive the new token. The relay stores only its SHA-256 digest and compares digests in constant time.

This release does not provide per-device credentials, rate limiting, audit identities, or remote token revocation while the process is running. Network firewalling and a rate-limiting reverse proxy are recommended for Internet exposure.

### Compromised relay host or administrator

An administrator can copy ciphertext, delete it, withhold updates, fork views between clients, replay old valid envelopes, alter revision metadata, and perform offline guesses against the encrypted key check. AES-GCM detects modified ciphertext but cannot by itself prove freshness or availability.

High-entropy sync passwords reduce offline-guessing risk. Clients must surface version regression and unexpected vault-identity changes. Strong rollback/fork detection across a fully malicious server requires an external transparency log or independently exchanged client checkpoints and is not implemented.

### Database or attachment corruption

SQLite transactions and full synchronization protect completed metadata updates. Attachment uploads are hashed, fsynced, atomically renamed, and then referenced transactionally. Startup removes abandoned temporary and unreferenced files. Clients verify the complete ciphertext SHA-256 and AES-GCM tag before accepting downloaded content.

Checksums detect accidental corruption but do not replace authenticated encryption. Operators need consistent tested backups; the relay cannot recreate missing ciphertext.

### Replay and duplicate requests

Every mutation has a durable globally unique operation ID. Exact replay returns the stored result after process restart; altered reuse fails. Item mutations also require the last observed opaque version token. This prevents duplicated revisions and blind last-writer overwrite under normal server operation.

A compromised server can replay an older internally valid response. Clients must retain their highest committed cursor and revision and reject regression. This does not solve a coordinated long-lived server fork.

### Malicious payloads

The relay validates bounds, identifiers, Base64, and ciphertext checksums but deliberately does not parse decrypted documents. Encrypted attachments are stored under SHA-256-derived server filenames, not external filenames. Client import validation and sandboxed viewers remain responsible for malicious PDFs, images, archives, and documents after authenticated decryption.

### Lost secrets

A lost bearer token can be rotated if the operator still controls the data directory. A lost sync password or client-side master key is unrecoverable by design. Neither the relay administrator nor a backend database can reset end-to-end encryption. Users need an independently protected recovery method or a known-good client that can re-encrypt into a new vault.

### Rooted or compromised client

End-to-end encryption does not protect plaintext displayed or decrypted by a compromised unlocked client. Android Keystore, biometric gating, desktop memory hygiene, screenshot controls, and short plaintext lifetimes reduce exposure but cannot defeat root, kernel compromise, screen capture by privileged malware, or a malicious accessibility service with sufficient access.

## Availability and residual risks

The relay is a single-vault, single-administrative-token service. It has no clustering, quota subsystem, per-client access revocation, built-in rate limiter, append-only audit log, garbage-collection policy, or automatic off-site backup. Tombstones and operation records are retained indefinitely, so storage use grows over time.

mDNS is best-effort and often blocked by guest Wi-Fi or hotspot isolation. Automatic discovery is therefore a convenience layer, not a reliability or security guarantee.

The strongest current guarantees are confidentiality and integrity of correctly implemented client envelopes, authenticated relay transport, durable optimistic concurrency, and safe retry. Availability against a malicious host, metadata privacy, server-fork detection, compromised endpoints, and recovery from a lost encryption secret remain unmitigated.
