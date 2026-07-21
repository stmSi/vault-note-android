# VaultNote threat model

## Assets and assumptions

Assets are note and OCR text, tag and attachment metadata, attachment plaintext, encryption keys, backup passwords and archives, future production authentication tokens, and deletion state. The current adversary may control imported data, a backup archive/document provider, another ordinary Android app, an external viewer, a future cloud/backend, or physical possession of the phone.

VaultNote assumes the Android OS, verified boot, sandbox, Keystore implementation, device credential screen, and installed app binary are trustworthy. Root, kernel compromise, runtime instrumentation, malicious accessibility/device-admin services, and an attacker observing an already unlocked screen can violate those assumptions.

## Scenario assessment

| Threat | Current status | Controls and residual risk |
| --- | --- | --- |
| Stolen unlocked phone | Partially mitigated | Immediate background locking can quickly cover the UI and attachment provider, and recents are concealed. Content visible before the timeout and plaintext Room data available through a compromised/unlocked process remain exposed. |
| Stolen locked phone | Mitigated for attachments under assumptions | Ciphertext is AES-256-GCM and its key is non-exportable in Keystore; the app starts fail-closed when lock is enabled. Plaintext Room/search data still relies only on sandbox/device storage protection. Weak device credentials and OS exploits remain risks. |
| Malware with limited app access | Mitigated | App-private storage, no broad storage permission, non-exported providers, exact URI grants, and Keystore isolation deny ordinary cross-app reads. Malware with accessibility, root, backup/debug, or code-execution privileges is not “limited.” |
| Malicious imported files | Substantially mitigated | URI scheme, filename, extension, MIME, signature, size, space, UTF-8/container integrity, image/PDF metadata, and path confinement are validated. Reads are bounded/streamed and files are never executed. Platform decoders/viewers may still contain vulnerabilities; external viewers are outside the boundary. |
| Compromised cloud storage | Interface mitigates attachment disclosure; no production service yet | The protocol uploads authenticated attachment ciphertext and no plaintext key. The Phase 5 fake retains no bytes and is not backup. Traffic analysis, deletion, rollback, and metadata exposure remain risks for a future service. |
| Compromised backend administrator | Not mitigated for metadata | The documented protocol sends note/title/tag/OCR metadata as application payload. A production administrator could read or maliciously version it. Attachment plaintext remains encrypted, but server-enforced revisions cannot by themselves defeat a malicious server. |
| Lost credentials | Not yet mitigated | No production account exists. Future recovery must distinguish account access from end-to-end encryption-key recovery and must not silently weaken either. |
| Lost encryption keys | Mitigated only with an independent backup | Keystore loss or app-data clearing destroys access to installation ciphertext. A previously exported backup can recover content without the old Keystore key. Encrypted backups require their password; unencrypted backups expose their content to anyone with file access. Without a usable archive, recovery is impossible. |
| Corrupted backups | Substantially mitigated | Strict manifests, exact entry sets, bounded ZIP parsing, SHA-256, content validation and private staging reject accidental damage before live changes. Encrypted version `1` additionally uses AES-GCM authenticity. Unencrypted version `2` checksums can be recomputed by an attacker and therefore do not establish authorship. Loss of the only valid archive or encrypted-backup password is not recoverable. |
| Replay attacks | Partially mitigated | GCM context prevents attachment substitution. Operation IDs are idempotency keys, expected version tokens reject ordinary stale writes, and cursors commit transactionally. A compromised server can still roll back its own tokens/history; independent signed transparency is not implemented. A valid older manual backup can be restored because no trusted external monotonic backup counter exists. |
| Sync conflicts | Mitigated for concurrent item content | Local/remote/last-synced revisions and server tokens detect divergence. The app preserves local and remote copies, preserves local edits on remote deletion, and requires explicit version selection. Attachment-set conflicts and sophisticated field-level merges remain limited. |
| Accidental deletion | Partially mitigated | Notes use soft deletion and Trash/restore. A prior manual backup provides independent recovery. Attachment deletion has a durable cleanup journal but no user-facing attachment trash; remote tombstone acknowledgement and retention are deferred. |
| Rooted devices | Not mitigated | Root/runtime instrumentation may read Room, plaintext while displayed/streamed, app memory, or invoke Keystore operations in-process. VaultNote does not claim root resistance and should warn rather than pretend otherwise. |

## Security properties tested

- Encryption/decryption round trip with bounded streaming.
- Different envelopes for the same plaintext through unique nonces.
- Corrupted GCM tag produces a typed failure and zero output bytes.
- Attachment identity and attachment/thumbnail purpose are authenticated.
- Old key versions remain readable after a test rotation; missing historical keys fail without output.
- Legacy migration checks the persisted SHA-256 before encryption and changes Room version only after success.
- Lock state fails closed, respects monotonic background timeouts, and does not treat rotation as backgrounding.
- External viewer/share grants are random, attachment-bound, expire after five minutes, permit at most eight content reads for viewer compatibility, and disappear on process death.
- External handoff plaintext is authenticated before a seekable descriptor is exposed; the private cache name is immediately unlinked. Explicit save failures delete or truncate partial output where the selected document provider permits it.
- Search input is bounded and compiled to quoted prefix terms instead of raw FTS syntax; attachment filenames are verified through the public search repository.
- OCR transitions, checksum-based unchanged-file avoidance, FTS updates, and retry classification are verified with deterministic fakes.
- Per-character prefix search begins with one letter and never accepts raw FTS operators.
- Sync operation IDs are idempotent, acknowledgements persist server revisions/tokens, authentication expiry stops work, retry delay is bounded, and concurrent content preserves both versions until explicit resolution.
- Backup entries use fresh nonces and authenticate their manifest binding and exact path; wrong passwords, corruption and path substitution expose zero plaintext to the caller.
- Backup manifests reject unknown versions/extensions and malformed cryptographic parameters.
- Unencrypted backup export is explicit and defaults off; round-trip and tampering tests verify structural staging and accidental-corruption detection without claiming keyed authenticity.
- Restore rejects traversal paths before parsing payload data, keeps Room unchanged during staging, and preserves colliding item IDs as independent copies on commit.

Instrumentation on a physical device is still required to validate vendor BiometricPrompt behavior, screenshot/recents policy, Android Keystore persistence, external viewers, process recreation, and encrypted import/open flows. JVM cryptographic tests use deterministic in-memory keys and do not substitute for device Keystore verification.

## Deferred mitigations

- Whole-database or field-level protection for note/search plaintext.
- Root/jailbreak signal and user warning policy.
- Recovery when both the only backup password and all valid archives are lost; password escrow is intentionally absent.
- Production authentication/backend, stronger malicious-server rollback detection, metadata end-to-end encryption, attachment download materialization, and server tombstone retention policy.
- OCR quality for arbitrary scripts, handwriting, and hostile decoder inputs; VaultNote uses bounded Android decoders and the bundled Latin ML Kit model, but this native/library boundary remains in the threat surface.
- Forensic erasure of OCR cache leases on flash storage; the app provides logical deletion and bounded stale cleanup only.
