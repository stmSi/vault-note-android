# VaultNote threat model

## Assets and assumptions

Assets are note and OCR text, tag and attachment metadata, attachment plaintext, encryption keys, future authentication tokens, deletion state, and future backups. The current adversary may control imported data, another ordinary Android app, an external viewer, cloud storage in a future phase, or physical possession of the phone.

VaultNote assumes the Android OS, verified boot, sandbox, Keystore implementation, device credential screen, and installed app binary are trustworthy. Root, kernel compromise, runtime instrumentation, malicious accessibility/device-admin services, and an attacker observing an already unlocked screen can violate those assumptions.

## Scenario assessment

| Threat | Current status | Controls and residual risk |
| --- | --- | --- |
| Stolen unlocked phone | Partially mitigated | Immediate background locking can quickly cover the UI and attachment provider, and recents are concealed. Content visible before the timeout and plaintext Room data available through a compromised/unlocked process remain exposed. |
| Stolen locked phone | Mitigated for attachments under assumptions | Ciphertext is AES-256-GCM and its key is non-exportable in Keystore; the app starts fail-closed when lock is enabled. Plaintext Room/search data still relies only on sandbox/device storage protection. Weak device credentials and OS exploits remain risks. |
| Malware with limited app access | Mitigated | App-private storage, no broad storage permission, non-exported providers, exact URI grants, and Keystore isolation deny ordinary cross-app reads. Malware with accessibility, root, backup/debug, or code-execution privileges is not “limited.” |
| Malicious imported files | Substantially mitigated | URI scheme, filename, extension, MIME, signature, size, space, UTF-8/container integrity, image/PDF metadata, and path confinement are validated. Reads are bounded/streamed and files are never executed. Platform decoders/viewers may still contain vulnerabilities; external viewers are outside the boundary. |
| Compromised cloud storage | Not yet applicable; future design required | Phase 4 performs no cloud upload. Future storage must receive only authenticated encrypted attachments and must not receive plaintext keys. Metadata confidentiality is not yet designed. |
| Compromised backend administrator | Not yet applicable; future design required | There is no backend. The future protocol must minimize visible metadata and make revision/token manipulation detectable where possible; server-side access to any unencrypted metadata remains a residual risk. |
| Lost credentials | Not yet mitigated | No production account exists. Future recovery must distinguish account access from end-to-end encryption-key recovery and must not silently weaken either. |
| Lost encryption keys | Not mitigated | Keystore loss or app-data clearing makes attachments unrecoverable. Versioned aliases prevent accidental retirement during rotation, but an independent encrypted backup is deferred to Phase 6. |
| Corrupted backups | Not yet applicable | Manual backup/restore is not implemented. Phase 6 must authenticate manifests and entries, verify SHA-256, stage restore, reject traversal, and leave live data unchanged on failure. |
| Replay attacks | Locally constrained; remote protection deferred | GCM context prevents attachment substitution, and persistent local revisions prevent basic stale local replacement. There is no remote protocol yet; server revision tokens, cursors, tombstones, and idempotency keys are Phase 5 requirements. |
| Sync conflicts | State prepared, resolution not implemented | Local/remote/last-synced revision fields and durable operations exist, but the fake scheduler performs no synchronization. Concurrent content must later preserve both copies and never use device time as sole truth. |
| Accidental deletion | Partially mitigated | Notes use soft deletion and Trash/restore. Attachment deletion has a durable cleanup journal but no user-facing attachment trash; remote tombstone acknowledgement and retention are deferred. No independent backup exists. |
| Rooted devices | Not mitigated | Root/runtime instrumentation may read Room, plaintext while displayed/streamed, app memory, or invoke Keystore operations in-process. VaultNote does not claim root resistance and should warn rather than pretend otherwise. |

## Security properties tested through Phase 4

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

Instrumentation on a physical device is still required to validate vendor BiometricPrompt behavior, screenshot/recents policy, Android Keystore persistence, external viewers, process recreation, and encrypted import/open flows. JVM cryptographic tests use deterministic in-memory keys and do not substitute for device Keystore verification.

## Deferred mitigations

- Whole-database or field-level protection for note/search plaintext.
- Root/jailbreak signal and user warning policy.
- Encrypted manual backup and key recovery.
- Authenticated remote protocol, rollback/replay defenses, real credentials, conflict resolution, and remote deletion retention.
- OCR quality for arbitrary scripts, handwriting, and hostile decoder inputs; Phase 4 uses bounded Android decoders and the bundled Latin ML Kit model, but this native/library boundary remains in the threat surface.
- Forensic erasure of OCR cache leases on flash storage; the app provides logical deletion and bounded stale cleanup only.
