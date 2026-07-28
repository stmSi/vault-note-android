# Production synchronization integration status

The repository now includes a versioned opaque relay contract and implementation under `sync-server/`. The exact HTTPS endpoints, persistent cursor/revision rules, idempotency behavior, encrypted envelope, attachment streaming, error model, certificate pinning, and DNS-SD advertisement are documented in `sync-server/docs/wire-protocol.md`.

The desktop client deliberately retains its persistent fake-sync implementation until its protocol-3 client is complete. It must not mark local rows synchronized merely because the fake accepted them. Integration still requires:

1. A locked-vault state that releases neither the relay token nor derived content keys.
2. Protocol-3 PBKDF2/HKDF and AES-256-GCM envelope code with shared compatibility fixtures.
3. Secure first pairing and retained vault-ID/certificate-pin validation.
4. DNS-SD discovery for already paired LAN or hotspot relays plus manual-address fallback.
5. Streamed, resumable attachment download and restartable upload without plaintext staging.
6. Transactional application of incremental pages and durable conflict copies.
7. End-to-end tests against the real relay for first sync, offline edits, concurrent edits, token rotation, retries, tombstones, replay, corruption, and process restart.

Tokens and HTTP logic must remain in Rust. The relay token should be encrypted under a vault-derived subkey and remain unavailable while the vault is locked; command responses must expose only non-sensitive sync status, and the existing SQLite operation table remains the durable source of pending work.
