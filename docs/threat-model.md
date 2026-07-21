# VaultNote threat model

## Assets and assumptions

Assets are note and OCR text, tag and attachment metadata, attachment plaintext, encryption keys, future production authentication tokens, deletion state, and future backups. The current adversary may control imported data, another ordinary Android app, an external viewer, a future cloud/backend, or physical possession of the phone.

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
| Lost encryption keys | Not mitigated | Keystore loss or app-data clearing makes attachments unrecoverable. Versioned aliases prevent accidental retirement during rotation, but an independent encrypted backup is deferred to Phase 6. |
| Corrupted backups | Not yet applicable | Manual backup/restore is not implemented. Phase 6 must authenticate manifests and entries, verify SHA-256, stage restore, reject traversal, and leave live data unchanged on failure. |
| Replay attacks | Partially mitigated | GCM context prevents attachment substitution. Operation IDs are idempotency keys, expected version tokens reject ordinary stale writes, and cursors commit transactionally. A compromised server can still roll back its own tokens/history; independent signed transparency is not implemented. |
| Sync conflicts | Mitigated for concurrent item content | Local/remote/last-synced revisions and server tokens detect divergence. The app preserves local and remote copies, preserves local edits on remote deletion, and requires explicit version selection. Attachment-set conflicts and sophisticated field-level merges remain limited. |
| Accidental deletion | Partially mitigated | Notes use soft deletion and Trash/restore. Attachment deletion has a durable cleanup journal but no user-facing attachment trash; remote tombstone acknowledgement and retention are deferred. No independent backup exists. |
| Rooted devices | Not mitigated | Root/runtime instrumentation may read Room, plaintext while displayed/streamed, app memory, or invoke Keystore operations in-process. VaultNote does not claim root resistance and should warn rather than pretend otherwise. |

## Security properties tested through Phase 5

- Encryption/decryption round trip with bounded streaming.
- Different envelopes for the same plaintext through unique nonces.
- Corrupted GCM tag produces a typed failure and zero output bytes.
- Attachment identity and attachment/thumbnail purpose are authenticated.
- Old key versions remain readable after a test rotation; missing historical keys fail without output.
- Legacy migration checks the persisted SHA-256 before encryption and changes Room version only after success.
- Lock state fails closed, respects monotonic background timeouts, and does not treat rotation as backgrounding.
- External viewer grants are random, attachment-bound, expiring, and one use.
- Search input is bounded and compiled to quoted prefix terms instead of raw FTS syntax; attachment filenames are verified through the public search repository.
- OCR transitions, checksum-based unchanged-file avoidance, FTS updates, and retry classification are verified with deterministic fakes.
- Per-character prefix search begins with one letter and never accepts raw FTS operators.
- Sync operation IDs are idempotent, acknowledgements persist server revisions/tokens, authentication expiry stops work, retry delay is bounded, and concurrent content preserves both versions until explicit resolution.

Instrumentation on a physical device is still required to validate vendor BiometricPrompt behavior, screenshot/recents policy, Android Keystore persistence, external viewers, process recreation, and encrypted import/open flows. JVM cryptographic tests use deterministic in-memory keys and do not substitute for device Keystore verification.

## Deferred mitigations

- Whole-database or field-level protection for note/search plaintext.
- Root/jailbreak signal and user warning policy.
- Encrypted manual backup and key recovery.
- Production authentication/backend, stronger malicious-server rollback detection, metadata end-to-end encryption, attachment download materialization, and server tombstone retention policy.
- OCR quality for arbitrary scripts, handwriting, and hostile decoder inputs; Phase 5 uses bounded Android decoders and the bundled Latin ML Kit model, but this native/library boundary remains in the threat surface.
- Forensic erasure of OCR cache leases on flash storage; the app provides logical deletion and bounded stale cleanup only.
