# Production synchronization inputs required

The desktop client deliberately retains the persistent fake-sync implementation until the real backend contract is supplied. Inventing an endpoint or token flow would create an incompatible and potentially insecure protocol.

Implementation requires these decisions and fixtures:

1. The production HTTPS base URL, supported environments, minimum TLS policy, and whether certificate pinning is required.
2. The authentication flow: provider, authorization/token endpoints, client registration, redirect URI, scopes, refresh/rotation behavior, logout/revocation, and server clock-skew rules.
3. Exact request/response schemas for item upsert, tombstone delete, incremental pull, attachment upload/delete/download, and remote acknowledgements.
4. Pagination/cursor rules, idempotency keys, maximum payload sizes, attachment chunking, content checksums, and retryable versus permanent HTTP/application errors.
5. Conflict semantics for local revision, remote revision, version token, deleted records, attachment changes, and retention of conflict copies.
6. Compatibility fixtures or a staging server covering first sync, offline edits, concurrent edits, token expiry, retries, tombstones, replay, and corrupted attachment transfer.

Once supplied, tokens and HTTP logic must remain in Rust. Refresh tokens should be encrypted under a vault-derived subkey and remain unavailable while the vault is locked; command responses must expose only non-sensitive sync status, and the existing SQLite operation table should remain the durable source of pending work.
