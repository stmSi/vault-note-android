# VaultNote security model

## Current boundary

Phase 5 protects imported attachments and thumbnails with versioned AES-256-GCM envelopes backed by Android Keystore and adds offline search, OCR, and durable synchronization plumbing. It retains the optional biometric/device-credential app lock, automatic background timeout, secure recent-apps treatment, configurable screenshot blocking, and authenticated streaming content provider.

This is not whole-vault encryption. Note titles, bodies, tag names, OCR fields, attachment display names, and the Room FTS index remain plaintext in the app-private database. Anyone who can extract app data or execute in the app process may access those values. This limitation must remain visible until a separately designed database/search encryption strategy exists.

OCR requires plaintext input. Only after the encrypted envelope authenticates, VaultNote creates a random app-private cache lease, processes bounded image data or one PDF page at a time, and logically deletes the lease on completion or cancellation. A process-killed lease is removed when older than one hour on the next OCR pass. Filesystem and flash-controller behavior means the app cannot guarantee forensic secure erasure. Locking cancels active OCR, but an OS- or process-level compromise remains outside this boundary.

## Trust boundaries

- Android's application sandbox confines Room, ciphertext, thumbnails, and pending files from ordinary apps.
- Android Keystore holds non-exportable attachment keys. VaultNote stores only an integer key version in each envelope.
- The device lock and `BiometricPrompt` authenticate the user; VaultNote never receives biometric material, a PIN, pattern, or password.
- Room remains the only displayed source of truth. The replaceable sync boundary consumes a durable queue; the included in-memory backend contains no credential, stores no attachment bytes, and is not remote backup.
- Attachment ciphertext can cross the sync boundary, but note/title/tag/OCR metadata is not end-to-end encrypted. A future production backend administrator could read that metadata unless the protocol changes.
- Imported providers, filenames, MIME claims, sizes, file contents, camera apps, external viewers, and future remote systems are untrusted.

## App lock

App lock is optional and defaults off so upgrading users are not locked out unexpectedly. Enabling it requires a successful strong biometric or device-credential prompt. Supported background timeouts are immediate, 30 seconds, 1 minute, and 5 minutes. The timeout uses monotonic elapsed time rather than wall-clock time.

Lock state is process-local and starts fail-closed until the Room policy loads. With lock enabled, a new process starts locked. Backgrounding starts the configured timeout; rotation does not. Locking overlays an opaque fragment, disables accessibility traversal into underlying content, clears Coil's memory cache, blocks internal decrypted streams, and cancels legacy encryption maintenance.

BiometricPrompt on Android 11 and newer explicitly accepts `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`. Android 8–10 uses the compatibility API's device-credential-enabled prompt because that authenticator combination is not consistently supported there. Authentication gates a session and is not cryptographically bound to every attachment read.

Malformed or unreadable lock policy fails closed: lock enabled, immediate timeout, screenshots blocked. The settings screen exposes loading, content, error/retry, and save-failure states.

## Screenshots and recent apps

Recent-apps screenshots are disabled with the platform API on Android 13 and newer. Sensitive windows also use `FLAG_SECURE` while locked or when screenshot blocking is enabled. On Android 12L and older, `FLAG_SECURE` remains enabled because it is the available mechanism to conceal the recent-apps image; consequently screenshots cannot be independently enabled there.

These controls reduce accidental disclosure and ordinary screenshot capture. They do not stop another physical camera, accessibility/overlay abuse with elevated privileges, screen capture by a compromised OS, root, or all vendor-specific behavior.

## Attachment access

Ciphertext files are never exposed through `FileProvider`. That provider is restricted to the camera-capture cache. Internal thumbnails and attachment views use `content://<application-id>.secure-attachments/...` URIs served by a non-exported provider.

The provider:

- accepts only bounded safe attachment IDs and exact attachment/thumbnail paths;
- reads current metadata from Room and rejects anything not marked format `1`;
- requires an unlocked session for internal reads;
- authenticates the entire GCM envelope before writing plaintext;
- reports the validated filename, canonical MIME type, and size required by external apps;
- materializes verified plaintext only in private cache when a seekable descriptor is required, opens it read-only, and immediately unlinks the filename; and
- removes abandoned named handoff files older than ten minutes before creating another handoff.

An explicit open or share action issues a random 144-bit token bound to one attachment, valid for five minutes and at most eight content reads. Multiple reads are necessary because document viewers commonly probe, reopen, and seek. Metadata probes do not consume a read, a mismatched attachment cannot consume another attachment's token, at most 16 tokens remain live, and process death invalidates all of them. Android receives a narrow read URI grant and the canonical validated MIME type.

“Save a copy” streams authenticated plaintext directly to a user-created Storage Access Framework document. VaultNote never requests broad storage access or persists destination access. Cancellation and failure revoke the in-memory authorization and attempt to delete the partial document, falling back to truncation when deletion is unsupported. Once plaintext reaches a viewer, share recipient, or selected document provider, that app/provider and the operating system are outside VaultNote's confidentiality boundary.

## Key lifecycle and recovery

The attachment key does not leave Android Keystore. There is currently no recovery phrase, escrow key, cloud key copy, or manual encrypted backup. Clearing app data removes the database and keys; loss or invalidation of a required historical key makes corresponding files unavailable. Versioned aliases and envelopes provide the structural seam for key rotation, but automated rotation UI is not part of Phase 3.

The app intentionally sets `allowBackup=false` and does not rely on Android Auto Backup. Phase 6 must provide a password/key-derived, authenticated, versioned manual backup staged and validated before restore.

## Operational rules

- Never log note content, OCR, sensitive filenames, decrypted paths, tokens, key aliases tied to user records, raw failures containing private responses, or authentication material.
- Never place plaintext keys or passwords in preferences, Room, Gradle, source, saved state, or intents.
- Never weaken a failure by marking ciphertext or metadata valid. Authentication and migration failures keep the previous state and are retryable only where meaningful.
- Keep debug signing and local SDK configuration outside version control. Production release signing must be supplied externally.
- Treat rooted devices, debug builds, compromised OS components, and an already-compromised app process as outside the guarantees of the application sandbox.

See [Encryption format](encryption-format.md) for the byte-level protocol and [Threat model](threat-model.md) for scenario coverage.
