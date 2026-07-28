# Desktop production synchronization

VaultNote Desktop implements protocol 3 against the opaque HTTPS relay in `sync-server/`. The UI never renders relay responses directly: validated remote pages commit to SQLite first, then the existing local queries refresh the screen.

## Implemented behavior

- The normal Android-to-desktop path needs no separately installed relay. VaultNote Desktop embeds the same protocol-3 relay library, creates its private identity under the desktop app-data directory, binds to the LAN, and advertises itself through mDNS after the user selects **Start phone sync**.
- Once enabled, the embedded host starts asynchronously on later desktop launches. It remains available while the desktop process is running, including while a password-protected local vault is locked, because the host stores only opaque encrypted relay records and has no sync content key.
- First enablement self-pairs the desktop client and briefly displays the phone token. Android discovers the desktop, pins the displayed certificate fingerprint, and uses the same sync password. The token is never written to a plaintext convenience file.
- A standalone `sync-server` remains supported for advanced always-on or multi-desktop deployments, but it is not required for ordinary local-network use.
- Manual pairing requires host, port, relay token, sync password, vault identity, pinned TLS SHA-256 fingerprint, and explicit fingerprint confirmation.
- `_vaultnote-sync._tcp.local.` discovery advertises no credentials. A paired endpoint may move only when both the vault ID and pinned fingerprint match.
- The relay token and derived sync master key are stored in an authenticated local credential envelope. An encrypted local vault protects that envelope with a vault-derived key. An unencrypted local vault requires the separate sync password again after process restart.
- Tokens and content keys remain in Rust and are released with the unlocked vault service lifetime. Command responses expose only bounded, non-sensitive status.
- PBKDF2/HKDF, AES-256-GCM item/key-check envelopes, and streamed attachment envelopes match Android protocol 3.
- Persistent SQLite queue rows use leases, process-death recovery, bounded exponential retry, stable operation IDs, and stable encrypted artifacts for exact replay.
- A sync run pulls remote changes, uploads local attachments and item snapshots, then pulls again. This order prevents a stale local write from hiding a concurrent remote change.
- Each incremental page and its cursor commit in one SQLite transaction. Unsupported but valid future item variants are retained in an opaque deferred table.
- Concurrent note changes preserve both bodies. The existing local item remains queued and a linked remote conflict copy is inserted; remote deletion with local changes preserves the local content.
- Attachment upload is complete before item metadata references its remote path. Downloads resume into private ciphertext partials and authenticate before installation.
- Sync status exposes queue counts, last attempt/success times, and the last committed relay revision.

The normative wire contract, TLS rules, envelope layout, revision behavior, attachment transfer, and relay error model are documented in `sync-server/docs/wire-protocol.md`. Android-facing behavior remains documented in `android/docs/sync-protocol.md`.

## Security boundaries

The relay is a rendezvous and durable opaque store, not a trusted content endpoint. It can observe vault and object identifiers, ciphertext sizes, revisions, request timing, IP addresses, and tombstones. It cannot decrypt item metadata or attachments without the sync password.

mDNS is only a reachability hint. It cannot establish trust, rotate a certificate, replace a vault ID, or disclose a token. Certificate verification validates both the exact pin and a valid certificate signature before any bearer token is sent.

The sync password is not persisted. Pairing credentials necessarily pass through the local UI process once, and the fields are cleared after each attempt; stored tokens, password-derived keys, decrypted relay payloads, raw private responses, and attachment plaintext paths are never returned to JavaScript. Local vault compromise while unlocked and a compromised client process remain outside the protection boundary.

## Operational limits

- Item plaintext: 2 MiB protocol bound, with stricter desktop note-field limits.
- Attachment plaintext: 100 MiB.
- Incremental page: at most 200 changes.
- Queue retry delay: 5 seconds doubling to a 6-hour cap.
- Queue lease: 60 seconds.

Phone hotspots and some access points suppress multicast, so Android retains manual host/port entry as a fallback. A desktop firewall may require one approval for inbound VaultNote traffic. Automatic client sync runs every 45 seconds only while both the local vault and sync credentials are unlocked; the embedded opaque host itself can remain available while the local vault is locked.

## Focused verification

Desktop tests cover Android-compatible wire field names, envelope context binding, attachment corruption before output, certificate-pin rejection, credential-envelope tampering, password-protected restart behavior, expired queue lease recovery, bounded retry, and concurrent-edit copy preservation. Relay API tests cover persistent idempotency, revisions, tombstones, authorization, upload verification, and range download.

Remaining release validation is an on-device interoperability pass using one Android client, one packaged desktop client, and the same LAN relay. That pass should exercise first pairing, two-way attachment sync, offline concurrent edits, remote deletion, process restart during transfer, wrong sync password, relay token rotation, and network relocation.
