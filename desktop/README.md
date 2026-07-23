# VaultNote for Windows and Linux

This directory contains the Tauri 2 desktop client. Rust owns persistence, SQL, encryption, key derivation, native file dialogs, backup processing, validation, and fake synchronization. Svelte receives bounded typed models and never receives a database key, SQL statement, attachment path, or arbitrary filesystem path.

## Included

- Active, archived, and trashed note lists with loading, empty, content, and error states.
- Plain-text note creation/editing with serialized 400 ms autosave and explicit retry state.
- Pin, favorite, archive, soft-delete, restore, attachment import/export, and filename search.
- SQLite schema v5 with optional SQLCipher encryption, forward migrations, and FTS5 prefix search. SQLite remains the offline source of truth.
- Persistent per-item synchronization operations and a deterministic local fake-sync pass.
- First-run choice between password-encrypted and explicitly unencrypted local storage. No OS credential manager is required.
- Rust-only SQLCipher/attachment key material in password mode; unencrypted mode uses no hidden key or password.
- Password-encrypted `.vnb` export/restore compatible with VaultNote's Android encrypted format v1 for note records and supported attachment types.
- Strict production CSP, packaged assets only, an empty Tauri capability permission list, and typed validated commands.

## Architecture

```text
Svelte UI state
  → typed invoke wrapper
    → validated Tauri command
      → service/authentication boundary
        → Rust repository, crypto, backup, or native dialog
          → SQLite transaction / mode-matched attachment store
```

Every note mutation commits its local revision, derived FTS document, sync status, and coalesced durable operation in one transaction. A failed or absent sync pass cannot lose a local edit. Identical autosaves are no-ops.

In password mode, the vault password is processed by Argon2id with a random 128-bit salt, 64 MiB of memory, three iterations, and one lane. Rust derives the 256-bit SQLCipher/attachment key in memory. A fixed private `vault-key.json` file stores only the salt, bounded KDF parameters, and a verifier; it stores neither the password nor the database key. The password is required after every process launch and cannot be recovered.

In unencrypted mode, `vaultnote.db` remains ordinary readable SQLite and attachments are stored as readable files inside the fixed application-data directory. A private `vault-plaintext.json` marker records the deliberate mode choice. There is no password, derived key, hidden random key, or Lock action. SQL and filesystem paths remain behind validated Rust commands in both modes.

In password mode, attachment ciphertext uses per-record HKDF-derived keys and chunked AES-256-GCM. In unencrypted mode, attachment copies remain plaintext. Imports and exports use Rust native dialogs; paths are never accepted from or returned to Svelte. Backup v1 remains password encrypted in either mode and uses PBKDF2-HMAC-SHA256 (600,000 iterations), independently nonced AES-256-GCM entries, path/manifest authenticated data, SHA-256 transport checks, strict ZIP paths, size limits, and atomic final writes where the destination filesystem supports rename.

## Run in development

Requirements:

- Rust 1.88 or newer.
- Node.js supported by Vite 8.
- Linux: GTK 3, WebKitGTK 4.1, XDG Desktop Portal with a desktop file-picker backend, OpenSSL, and normal native build tools.
- Windows: Microsoft C++ Build Tools and WebView2. Windows has not been compiled in this workspace.

From this directory:

```bash
npm install
npm run tauri dev
```

On first launch, choose one mode:

- **Password encryption (recommended):** enter a 12–128 character vault password. An existing readable plaintext desktop database is atomically migrated to SQLCipher; subsequent launches require the same password.
- **No password or encryption:** SQLite and attachments remain plaintext and the vault opens automatically on later launches.

KDE Wallet, GNOME Keyring, Secret Service, and Windows Credential Manager are not used in either mode.

Linux file selection uses the asynchronous XDG Desktop Portal picker. File and backup paths stay inside Rust and are never returned to Svelte; database or file processing starts only after the portal returns a selection.

## Verify and build

```bash
npm run check
npm test
npm run build

cargo fmt --manifest-path src-tauri/Cargo.toml --all -- --check
cargo check --manifest-path src-tauri/Cargo.toml --all-targets
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets --all-features -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml --all-targets

npm run bundle:linux
```

`bundle:linux` sets `NO_STRIP=1` for linuxdeploy. This is needed on current Arch Linux libraries containing `.relr.dyn`, which the older strip binary embedded in linuxdeploy cannot read. Rust's release profile still strips the application binary; this switch only prevents linuxdeploy from re-stripping bundled system libraries.

Linux bundles are written below `src-tauri/target/release/bundle/` as `.deb`, `.rpm`, and `.AppImage` artifacts.

## Security and compatibility limits

- The vault password is the root of local encryption. Losing it or `vault-key.json` makes the database and attachments unrecoverable without a verified encrypted backup.
- The salt and verifier permit offline password guessing if copied, so password strength matters. Argon2id raises the cost but cannot protect a weak password.
- Unencrypted mode provides no confidentiality at rest. Any person or program that can read the application-data directory can read or modify notes and attachments with ordinary SQLite/file tools.
- Switching an existing vault between encrypted and unencrypted modes is not currently supported. Use a verified encrypted backup before replacing a vault configuration.
- A compromised account, desktop session, OS, or process can capture the entered password or observe unlocked content.
- A migrated legacy plaintext SQLite file is replaced atomically and logically deleted, but flash storage cannot guarantee forensic erasure of old blocks.
- Backup restore imports independent local copies and never overwrites existing item or attachment IDs.
- Desktop restore accepts encrypted backup v1 only, caps archives at 512 MiB, accepts `NOTE` items, and accepts the attachment extensions listed in the UI import dialog. Android plaintext backup v2 and larger/mixed-item archives are rejected rather than partially restored.
- Backup entry encryption currently authenticates one bounded entry in memory. The 512 MiB archive and 100 MiB attachment limits bound memory use, but large restores can still require substantial RAM.
- Production network sync is not implemented. The repository has no server base URL, endpoint/wire contract, or authentication lifecycle. See [production sync requirements](docs/production-sync-requirements.md).
- RustSec reports no known vulnerabilities in the final lockfile, but reports upstream warnings for Tauri's required GTK3/GLib stack (including an unsound `glib::VariantStrIter` API the app does not call) and several unmaintained transitive build/runtime crates. Removing those warnings requires Tauri/Wry to migrate its Linux WebKit backend; track them when upgrading Tauri.

Dependencies are locked in `package-lock.json` and `src-tauri/Cargo.lock`.
