use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use hkdf::Hkdf;
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use uuid::Uuid;
use zeroize::Zeroizing;

use crate::{
    error::AppError,
    sync_crypto::{SyncMasterKey, sha256_bytes},
    sync_wire,
    vault_key::MasterKey,
};

const FORMAT_VERSION: u32 = 1;
const NONCE_BYTES: usize = 12;
const KEY_BYTES: usize = 32;
const MAX_FILE_BYTES: u64 = 32 * 1024;
const MAX_TOKEN_BYTES: usize = 128;
const CREDENTIAL_AAD_PREFIX: &[u8] = b"VaultNote Desktop Sync Credentials v1";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CredentialProtection {
    LocalVault,
    SyncPassword,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RelayPublicConfig {
    pub host_address: String,
    pub port: u16,
    pub dns_name: String,
    pub vault_id: String,
    pub certificate_sha256: String,
    pub kdf_algorithm: String,
    pub kdf_iterations: u32,
    pub kdf_salt: String,
    pub kdf_key_bits: u16,
    pub protection: CredentialProtection,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredConnection {
    version: u32,
    public: RelayPublicConfig,
    nonce: String,
    encrypted_secrets: String,
    ciphertext_sha256: String,
}

pub struct RelaySecrets {
    pub public: RelayPublicConfig,
    pub authentication_token: Zeroizing<String>,
    pub master_key: SyncMasterKey,
}

pub struct SyncCredentialStore {
    path: PathBuf,
    local_master_key: Option<Arc<MasterKey>>,
    active: Mutex<Option<Arc<RelaySecrets>>>,
}

impl SyncCredentialStore {
    pub fn new(
        app_data_directory: &Path,
        local_master_key: Option<Arc<MasterKey>>,
    ) -> Result<Self, AppError> {
        let store = Self {
            path: app_data_directory.join("sync-connection.json"),
            local_master_key,
            active: Mutex::new(None),
        };
        if store.local_master_key.is_some()
            && store.path.is_file()
            && let Ok(connection) = store.read_connection()
            && connection.public.protection == CredentialProtection::LocalVault
            && let Ok(secrets) = store.decrypt_with_local_key(&connection)
        {
            *store.active.lock().map_err(|_| AppError::StateLock)? = Some(Arc::new(secrets));
        }
        Ok(store)
    }

    pub fn public_config(&self) -> Result<Option<RelayPublicConfig>, AppError> {
        if !self.path.is_file() {
            return Ok(None);
        }
        Ok(Some(self.read_connection()?.public))
    }

    pub fn preferred_protection(&self) -> CredentialProtection {
        if self.local_master_key.is_some() {
            CredentialProtection::LocalVault
        } else {
            CredentialProtection::SyncPassword
        }
    }

    pub fn active(&self) -> Result<Arc<RelaySecrets>, AppError> {
        self.active
            .lock()
            .map_err(|_| AppError::StateLock)?
            .as_ref()
            .cloned()
            .ok_or_else(|| {
                if self.path.is_file() {
                    AppError::SyncLocked
                } else {
                    AppError::SyncNotConfigured
                }
            })
    }

    pub fn save_pairing(
        &self,
        public: RelayPublicConfig,
        authentication_token: &str,
        master_key: SyncMasterKey,
    ) -> Result<(), AppError> {
        public.validate()?;
        validate_token(authentication_token)?;
        if public.protection == CredentialProtection::LocalVault && self.local_master_key.is_none()
        {
            return Err(AppError::InvalidState);
        }
        let wrapping_key = match public.protection {
            CredentialProtection::LocalVault => self.local_wrapping_key(&public.vault_id)?,
            CredentialProtection::SyncPassword => {
                sync_password_wrapping_key(&master_key, &public.vault_id)?
            }
        };
        let include_master_key = public.protection == CredentialProtection::LocalVault;
        let plaintext = encode_secrets(authentication_token, &master_key, include_master_key)?;
        let stored = encrypt_connection(&public, &wrapping_key, &plaintext)?;
        self.write_connection(&stored)?;
        *self.active.lock().map_err(|_| AppError::StateLock)? = Some(Arc::new(RelaySecrets {
            public,
            authentication_token: Zeroizing::new(authentication_token.to_owned()),
            master_key,
        }));
        Ok(())
    }

    pub fn unlock_with_sync_password(&self, password: &str) -> Result<(), AppError> {
        let connection = self.read_connection()?;
        if connection.public.protection != CredentialProtection::SyncPassword {
            return Err(AppError::InvalidState);
        }
        let master_key = SyncMasterKey::derive(
            password,
            &connection.public.kdf_salt,
            connection.public.kdf_iterations,
        )
        .map_err(|_| AppError::InvalidCredentials)?;
        let wrapping_key = sync_password_wrapping_key(&master_key, &connection.public.vault_id)?;
        let mut plaintext = decrypt_connection(&connection, &wrapping_key)
            .map_err(|_| AppError::InvalidCredentials)?;
        let authentication_token = decode_secrets(&mut plaintext, false, None)
            .map_err(|_| AppError::InvalidCredentials)?;
        *self.active.lock().map_err(|_| AppError::StateLock)? = Some(Arc::new(RelaySecrets {
            public: connection.public,
            authentication_token: Zeroizing::new(authentication_token),
            master_key,
        }));
        Ok(())
    }

    pub fn update_endpoint(&self, host_address: &str, port: u16) -> Result<(), AppError> {
        validate_host(host_address)?;
        if port == 0 {
            return Err(AppError::InvalidInput {
                field: "relay_port",
                reason: "port is required".to_owned(),
            });
        }
        let mut connection = self.read_connection()?;
        connection.public.host_address = host_address.to_owned();
        connection.public.port = port;
        self.write_connection(&connection)?;
        let mut active = self.active.lock().map_err(|_| AppError::StateLock)?;
        if let Some(current) = active.as_ref() {
            if current.public.vault_id != connection.public.vault_id
                || current.public.certificate_sha256 != connection.public.certificate_sha256
            {
                return Err(AppError::RelayIdentity);
            }
            *active = Some(Arc::new(RelaySecrets {
                public: connection.public,
                authentication_token: Zeroizing::new(current.authentication_token.to_string()),
                master_key: SyncMasterKey::from_bytes(*current.master_key.as_bytes()),
            }));
        }
        Ok(())
    }

    pub fn disconnect(&self) -> Result<(), AppError> {
        *self.active.lock().map_err(|_| AppError::StateLock)? = None;
        match fs::remove_file(&self.path) {
            Ok(()) => {
                if let Some(parent) = self.path.parent() {
                    sync_directory(parent)?;
                }
                Ok(())
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(AppError::Storage(error)),
        }
    }

    fn decrypt_with_local_key(
        &self,
        connection: &StoredConnection,
    ) -> Result<RelaySecrets, AppError> {
        let wrapping_key = self.local_wrapping_key(&connection.public.vault_id)?;
        let mut plaintext = decrypt_connection(connection, &wrapping_key)?;
        let mut master_bytes = [0_u8; KEY_BYTES];
        let authentication_token = decode_secrets(&mut plaintext, true, Some(&mut master_bytes))?;
        Ok(RelaySecrets {
            public: connection.public.clone(),
            authentication_token: Zeroizing::new(authentication_token),
            master_key: SyncMasterKey::from_bytes(master_bytes),
        })
    }

    fn local_wrapping_key(&self, vault_id: &str) -> Result<Zeroizing<[u8; KEY_BYTES]>, AppError> {
        let local = self
            .local_master_key
            .as_ref()
            .ok_or(AppError::InvalidState)?;
        let hkdf = Hkdf::<Sha256>::new(
            Some(b"VaultNote desktop sync credential key v1"),
            local.as_bytes(),
        );
        let mut key = Zeroizing::new([0_u8; KEY_BYTES]);
        hkdf.expand(vault_id.as_bytes(), key.as_mut())
            .map_err(|_| AppError::Cryptography)?;
        Ok(key)
    }

    fn read_connection(&self) -> Result<StoredConnection, AppError> {
        let metadata = self
            .path
            .metadata()
            .map_err(|_| AppError::SyncNotConfigured)?;
        if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_FILE_BYTES {
            return Err(AppError::CorruptedSync);
        }
        let bytes = Zeroizing::new(fs::read(&self.path)?);
        let connection: StoredConnection =
            serde_json::from_slice(&bytes).map_err(|_| AppError::CorruptedSync)?;
        if connection.version != FORMAT_VERSION
            || connection.public.validate().is_err()
            || connection.ciphertext_sha256
                != sha256_bytes(
                    &STANDARD
                        .decode(&connection.encrypted_secrets)
                        .map_err(|_| AppError::CorruptedSync)?,
                )
        {
            return Err(AppError::CorruptedSync);
        }
        Ok(connection)
    }

    fn write_connection(&self, connection: &StoredConnection) -> Result<(), AppError> {
        let parent = self.path.parent().ok_or_else(|| {
            AppError::Storage(std::io::Error::other(
                "sync credential directory unavailable",
            ))
        })?;
        let encoded =
            Zeroizing::new(serde_json::to_vec(connection).map_err(|_| AppError::CorruptedSync)?);
        let temporary = parent.join(format!(
            ".sync-credentials-{}.tmp",
            Uuid::new_v4().hyphenated()
        ));
        let result = (|| {
            let mut file = private_file(&temporary)?;
            file.write_all(&encoded)?;
            file.flush()?;
            file.sync_all()?;
            replace_file(&temporary, &self.path)?;
            harden_file(&self.path)?;
            sync_directory(parent)
        })();
        if result.is_err() {
            let _ = fs::remove_file(temporary);
        }
        result
    }
}

impl RelayPublicConfig {
    pub fn validate(&self) -> Result<(), AppError> {
        validate_host(&self.host_address)?;
        if self.port == 0
            || self.dns_name.is_empty()
            || self.dns_name.len() > 253
            || !sync_wire::valid_id(&self.vault_id)
            || !sync_wire::lower_hex_sha256(&self.certificate_sha256)
            || self.kdf_algorithm != "PBKDF2-HMAC-SHA256"
            || self.kdf_iterations != 600_000
            || self.kdf_key_bits != 256
            || base64::engine::general_purpose::URL_SAFE_NO_PAD
                .decode(&self.kdf_salt)
                .ok()
                .is_none_or(|salt| salt.len() != KEY_BYTES)
        {
            return Err(AppError::RelayIdentity);
        }
        Ok(())
    }
}

fn encrypt_connection(
    public: &RelayPublicConfig,
    wrapping_key: &[u8; KEY_BYTES],
    plaintext: &[u8],
) -> Result<StoredConnection, AppError> {
    let mut nonce = [0_u8; NONCE_BYTES];
    OsRng.fill_bytes(&mut nonce);
    let cipher = Aes256Gcm::new_from_slice(wrapping_key).map_err(|_| AppError::Cryptography)?;
    let encrypted = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: &credential_aad(public),
            },
        )
        .map_err(|_| AppError::Cryptography)?;
    Ok(StoredConnection {
        version: FORMAT_VERSION,
        public: public.clone(),
        nonce: STANDARD.encode(nonce),
        encrypted_secrets: STANDARD.encode(&encrypted),
        ciphertext_sha256: sha256_bytes(&encrypted),
    })
}

fn decrypt_connection(
    connection: &StoredConnection,
    wrapping_key: &[u8; KEY_BYTES],
) -> Result<Zeroizing<Vec<u8>>, AppError> {
    let nonce = STANDARD
        .decode(&connection.nonce)
        .map_err(|_| AppError::CorruptedSync)?;
    if nonce.len() != NONCE_BYTES {
        return Err(AppError::CorruptedSync);
    }
    let encrypted = Zeroizing::new(
        STANDARD
            .decode(&connection.encrypted_secrets)
            .map_err(|_| AppError::CorruptedSync)?,
    );
    if sha256_bytes(&encrypted) != connection.ciphertext_sha256 {
        return Err(AppError::CorruptedSync);
    }
    let cipher = Aes256Gcm::new_from_slice(wrapping_key).map_err(|_| AppError::Cryptography)?;
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &encrypted,
                aad: &credential_aad(&connection.public),
            },
        )
        .map_err(|_| AppError::CorruptedSync)?;
    Ok(Zeroizing::new(plaintext))
}

fn credential_aad(public: &RelayPublicConfig) -> Vec<u8> {
    let mut aad = Vec::with_capacity(
        CREDENTIAL_AAD_PREFIX.len()
            + public.vault_id.len()
            + public.certificate_sha256.len()
            + public.kdf_salt.len()
            + 32,
    );
    aad.extend_from_slice(CREDENTIAL_AAD_PREFIX);
    add_field(&mut aad, public.vault_id.as_bytes());
    add_field(&mut aad, public.certificate_sha256.as_bytes());
    add_field(&mut aad, public.kdf_algorithm.as_bytes());
    aad.extend_from_slice(&public.kdf_iterations.to_be_bytes());
    add_field(&mut aad, public.kdf_salt.as_bytes());
    aad.extend_from_slice(&public.kdf_key_bits.to_be_bytes());
    aad.push(match public.protection {
        CredentialProtection::LocalVault => 1,
        CredentialProtection::SyncPassword => 2,
    });
    aad
}

fn add_field(output: &mut Vec<u8>, value: &[u8]) {
    output.extend_from_slice(&(value.len() as u32).to_be_bytes());
    output.extend_from_slice(value);
}

fn encode_secrets(
    authentication_token: &str,
    master_key: &SyncMasterKey,
    include_master_key: bool,
) -> Result<Zeroizing<Vec<u8>>, AppError> {
    validate_token(authentication_token)?;
    let length = u16::try_from(authentication_token.len()).map_err(|_| AppError::InvalidState)?;
    let mut output = Zeroizing::new(Vec::with_capacity(
        1 + 2 + authentication_token.len() + 1 + KEY_BYTES,
    ));
    output.push(1);
    output.extend_from_slice(&length.to_be_bytes());
    output.extend_from_slice(authentication_token.as_bytes());
    output.push(u8::from(include_master_key));
    if include_master_key {
        output.extend_from_slice(master_key.as_bytes());
    }
    Ok(output)
}

fn decode_secrets(
    plaintext: &mut Zeroizing<Vec<u8>>,
    expect_master_key: bool,
    master_key_output: Option<&mut [u8; KEY_BYTES]>,
) -> Result<String, AppError> {
    if plaintext.len() < 4 || plaintext[0] != 1 {
        return Err(AppError::CorruptedSync);
    }
    let token_length = u16::from_be_bytes([plaintext[1], plaintext[2]]) as usize;
    let token_end = 3_usize
        .checked_add(token_length)
        .ok_or(AppError::CorruptedSync)?;
    if token_end >= plaintext.len() {
        return Err(AppError::CorruptedSync);
    }
    let has_master = plaintext[token_end] == 1;
    let expected_length = token_end + 1 + if has_master { KEY_BYTES } else { 0 };
    if has_master != expect_master_key || plaintext.len() != expected_length {
        return Err(AppError::CorruptedSync);
    }
    let token = std::str::from_utf8(&plaintext[3..token_end])
        .map_err(|_| AppError::CorruptedSync)?
        .to_owned();
    validate_token(&token)?;
    if let Some(output) = master_key_output {
        output.copy_from_slice(&plaintext[token_end + 1..]);
    }
    Ok(token)
}

fn sync_password_wrapping_key(
    master_key: &SyncMasterKey,
    vault_id: &str,
) -> Result<Zeroizing<[u8; KEY_BYTES]>, AppError> {
    let hkdf = Hkdf::<Sha256>::new(
        Some(b"VaultNote desktop sync password credentials v1"),
        master_key.as_bytes(),
    );
    let mut key = Zeroizing::new([0_u8; KEY_BYTES]);
    hkdf.expand(vault_id.as_bytes(), key.as_mut())
        .map_err(|_| AppError::Cryptography)?;
    Ok(key)
}

fn validate_token(value: &str) -> Result<(), AppError> {
    if value.starts_with("vns_")
        && value.len() <= MAX_TOKEN_BYTES
        && value
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'_' | b'-'))
    {
        Ok(())
    } else {
        Err(AppError::InvalidInput {
            field: "relay_token",
            reason: "invalid token".to_owned(),
        })
    }
}

fn validate_host(value: &str) -> Result<(), AppError> {
    if !value.is_empty()
        && value.len() <= 253
        && !value.chars().any(char::is_whitespace)
        && !value.contains(['/', '\\', '@', '#', '?'])
    {
        Ok(())
    } else {
        Err(AppError::InvalidInput {
            field: "relay_host",
            reason: "invalid host".to_owned(),
        })
    }
}

fn private_file(path: &Path) -> Result<File, AppError> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    Ok(options.open(path)?)
}

#[cfg(unix)]
fn replace_file(source: &Path, destination: &Path) -> Result<(), AppError> {
    fs::rename(source, destination)?;
    Ok(())
}

#[cfg(not(unix))]
fn replace_file(source: &Path, destination: &Path) -> Result<(), AppError> {
    let parent = destination.parent().ok_or_else(|| {
        AppError::Storage(std::io::Error::other(
            "sync credential directory unavailable",
        ))
    })?;
    let backup = parent.join(format!(".sync-credentials-{}.bak", Uuid::new_v4()));
    if destination.exists() {
        fs::rename(destination, &backup)?;
    }
    if let Err(error) = fs::rename(source, destination) {
        let _ = fs::rename(&backup, destination);
        return Err(AppError::Storage(error));
    }
    let _ = fs::remove_file(backup);
    Ok(())
}

#[cfg(unix)]
fn harden_file(path: &Path) -> Result<(), AppError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    Ok(())
}

#[cfg(not(unix))]
fn harden_file(_path: &Path) -> Result<(), AppError> {
    Ok(())
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), AppError> {
    File::open(path)?.sync_all()?;
    Ok(())
}

#[cfg(not(unix))]
fn sync_directory(_path: &Path) -> Result<(), AppError> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn password_protected_connection_requires_sync_password_after_restart() {
        let directory = tempdir().expect("temporary directory should exist");
        let store =
            SyncCredentialStore::new(directory.path(), None).expect("store should initialize");
        let public = public(CredentialProtection::SyncPassword);
        let master = SyncMasterKey::derive(
            "correct horse battery staple",
            &public.kdf_salt,
            public.kdf_iterations,
        )
        .expect("key should derive");
        store
            .save_pairing(public, "vns_valid-token_123", master)
            .expect("pairing should save");
        drop(store);

        let reopened =
            SyncCredentialStore::new(directory.path(), None).expect("store should initialize");
        assert!(matches!(reopened.active(), Err(AppError::SyncLocked)));
        assert!(
            reopened
                .unlock_with_sync_password("wrong password")
                .is_err()
        );
        reopened
            .unlock_with_sync_password("correct horse battery staple")
            .expect("sync password should unlock");
        assert_eq!(
            reopened
                .active()
                .expect("secrets should load")
                .authentication_token
                .as_str(),
            "vns_valid-token_123"
        );
    }

    #[test]
    fn public_endpoint_tampering_cannot_change_pinned_identity() {
        let directory = tempdir().expect("temporary directory should exist");
        let store =
            SyncCredentialStore::new(directory.path(), None).expect("store should initialize");
        let public = public(CredentialProtection::SyncPassword);
        let master = SyncMasterKey::derive(
            "correct horse battery staple",
            &public.kdf_salt,
            public.kdf_iterations,
        )
        .expect("key should derive");
        store
            .save_pairing(public, "vns_valid-token_123", master)
            .expect("pairing should save");
        store
            .update_endpoint("192.168.10.4", 9999)
            .expect("endpoint should update");
        let updated = store
            .public_config()
            .expect("configuration should load")
            .expect("configuration should exist");
        assert_eq!(updated.host_address, "192.168.10.4");
        assert_eq!(
            updated.certificate_sha256,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
        let active = store.active().expect("active endpoint should update");
        assert_eq!(active.public.host_address, "192.168.10.4");
        assert_eq!(active.public.port, 9999);
    }

    fn public(protection: CredentialProtection) -> RelayPublicConfig {
        RelayPublicConfig {
            host_address: "127.0.0.1".to_owned(),
            port: 8787,
            dns_name: "vaultnote-test.local".to_owned(),
            vault_id: "vault_test".to_owned(),
            certificate_sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                .to_owned(),
            kdf_algorithm: "PBKDF2-HMAC-SHA256".to_owned(),
            kdf_iterations: 600_000,
            kdf_salt: base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([3_u8; 32]),
            kdf_key_bits: 256,
            protection,
        }
    }
}
