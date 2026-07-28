# Relay deployment and operation

## Build

The relay requires stable Rust 1.88 or newer.

```bash
cd sync-server
cargo build --locked --release
```

The optimized executable is `target/release/vaultnote-sync-server`. Install it and its data directory under a dedicated, unprivileged operating-system account. Do not install build tools, Cargo caches, or runtime data inside the Android or desktop application directories.

## Initialize once

Use an absolute data path on a locally protected disk:

```bash
vaultnote-sync-server init --data-directory /var/lib/vaultnote-relay
```

Initialization writes a private configuration, SQLite database location, attachment directory, self-signed TLS certificate, and private key. On Unix, the directory is mode `0700` and secrets are mode `0600`. The authentication token is printed once; save it in a password manager. It is not stored in plaintext by the relay.

The token is not the sync encryption password. Losing the sync password makes end-to-end encrypted data unrecoverable. Losing the relay token does not: while the relay is stopped, rotate it and re-pair clients:

```bash
vaultnote-sync-server rotate-token --data-directory /var/lib/vaultnote-relay
```

Restart the relay after rotation. An already running process retains the authentication hash it loaded at startup.

## Local Wi-Fi or hotspot mode

```bash
vaultnote-sync-server serve \
  --data-directory /var/lib/vaultnote-relay \
  --port 8787 \
  --lan
```

This binds HTTPS to all interfaces and advertises the relay through mDNS. Host firewalls should allow inbound TCP 8787 and UDP multicast DNS 5353 only on trusted local interfaces. Guest networks, access-point isolation, VPN rules, and phone hotspots may block either path.

Automatic discovery is safe only after pairing has pinned the vault ID and TLS certificate fingerprint. Initial pairing transfers the printed token and fingerprint together. Never configure a client to accept every self-signed certificate.

If multicast is unavailable, enter the relay address manually or use a pairing QR code. Keep certificate pinning enabled. A new DHCP address does not require re-pairing because the certificate identity remains stable.

## Loopback and private overlay mode

Without `--lan`, the default bind is loopback:

```bash
vaultnote-sync-server serve --data-directory /var/lib/vaultnote-relay
```

An explicit `--listen` address supports a private VPN or overlay network. Apply firewall policy so only paired devices can reach the service. The bearer token is a second control, not a replacement for network policy or TLS.

Protocol 3's built-in certificate is intended for direct, pinned connections. A public reverse proxy that terminates TLS presents a different certificate; clients must use an explicitly configured public-WebPKI trust mode rather than comparing it to the relay's built-in fingerprint. Do not silently fall back between trust modes. Keep the proxy-to-relay hop on loopback or authenticated private transport, preserve request bodies as streams, and allow at least the relay's 110 MiB encrypted-attachment limit.

## Back up the relay

The data directory contains:

```text
relay-config.json
relay-cert.pem
relay-key.pem
relay.sqlite3
relay.sqlite3-wal
relay.sqlite3-shm
attachments/
```

Stop the relay before a filesystem backup, or use a SQLite-aware snapshot plus a consistent attachment snapshot. Copying only `relay.sqlite3` while writes are active can omit WAL transactions. Copying only the database loses attachment ciphertext. Protect the TLS private key and authentication hash even though vault payloads remain encrypted.

Test restore into an isolated directory. A restored relay must keep the original config, database, attachments, certificate, and private key together so paired clients retain the same vault ID and TLS pin.

## Monitoring and failure handling

`GET /health` is intentionally unauthenticated and content-free. Monitor process liveness, HTTPS reachability, free disk space, file-descriptor pressure, and backup completion. Do not log authorization headers, request bodies, query results, attachment identifiers, or response payloads.

The relay uses SQLite WAL mode with full synchronization, immutable attachment files, and startup cleanup of abandoned transfer files. A full disk, damaged SQLite database, missing ciphertext file, or certificate mismatch fails closed and must be surfaced to the operator. Never “repair” these failures by deleting the database or regenerating identity files in place.

Upgrade by stopping the process, taking a consistent backup, installing the locked release binary, and starting it against the same data directory. The relay rejects a database schema newer than it understands.
