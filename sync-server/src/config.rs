use std::{
    fs::{self, File, OpenOptions},
    io::{BufReader, Read, Write},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rand_core::{OsRng, RngCore};
use rcgen::{CertifiedKey, generate_simple_self_signed};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::error::RelayError;

pub const PROTOCOL_VERSION: u32 = 3;
pub const KDF_ITERATIONS: u32 = 600_000;
pub const MAX_ITEM_ENVELOPE_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_ATTACHMENT_ENVELOPE_BYTES: u64 = 110 * 1024 * 1024;
pub const MAX_CHANGE_PAGE_SIZE: u32 = 200;
pub const CONFIG_FILENAME: &str = "relay-config.json";
const DATABASE_FILENAME: &str = "relay.sqlite3";
const ATTACHMENTS_DIRECTORY: &str = "attachments";
pub const TLS_CERTIFICATE_FILENAME: &str = "relay-cert.pem";
pub const TLS_PRIVATE_KEY_FILENAME: &str = "relay-key.pem";
const CONFIG_LIMIT_BYTES: u64 = 64 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RelayConfig {
    pub protocol_version: u32,
    pub vault_id: String,
    pub created_at_epoch_millis: i64,
    pub authentication_token_sha256: String,
    pub key_derivation: KeyDerivationConfig,
    pub tls: TlsIdentityConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KeyDerivationConfig {
    pub algorithm: String,
    pub iterations: u32,
    pub salt: String,
    pub key_bits: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TlsIdentityConfig {
    pub dns_name: String,
    pub certificate_sha256: String,
}

#[derive(Debug)]
pub struct InitializedRelay {
    pub config: RelayConfig,
    pub authentication_token: Zeroizing<String>,
}

impl RelayConfig {
    pub fn database_path(&self, data_directory: &Path) -> PathBuf {
        data_directory.join(DATABASE_FILENAME)
    }

    pub fn attachments_directory(&self, data_directory: &Path) -> PathBuf {
        data_directory.join(ATTACHMENTS_DIRECTORY)
    }

    pub fn validate(&self) -> Result<(), RelayError> {
        if self.protocol_version != PROTOCOL_VERSION
            || !is_valid_id(&self.vault_id)
            || self.created_at_epoch_millis <= 0
            || !is_lower_hex_sha256(&self.authentication_token_sha256)
            || self.key_derivation.algorithm != "PBKDF2-HMAC-SHA256"
            || self.key_derivation.iterations != KDF_ITERATIONS
            || self.key_derivation.key_bits != 256
            || !is_valid_local_dns_name(&self.tls.dns_name)
            || !is_lower_hex_sha256(&self.tls.certificate_sha256)
        {
            return Err(RelayError::InvalidConfiguration);
        }
        let salt = URL_SAFE_NO_PAD
            .decode(&self.key_derivation.salt)
            .map_err(|_| RelayError::InvalidConfiguration)?;
        if salt.len() != 32 {
            return Err(RelayError::InvalidConfiguration);
        }
        Ok(())
    }
}

pub fn initialize_relay(data_directory: &Path) -> Result<InitializedRelay, RelayError> {
    validate_data_directory(data_directory)?;
    fs::create_dir_all(data_directory)?;
    set_private_directory_permissions(data_directory)?;
    let config_path = data_directory.join(CONFIG_FILENAME);
    if config_path.exists() {
        return Err(RelayError::AlreadyInitialized);
    }

    let mut salt = [0_u8; 32];
    OsRng.fill_bytes(&mut salt);
    let authentication_token = generate_authentication_token();
    let vault_id = uuid::Uuid::new_v4().hyphenated().to_string();
    let dns_name = format!("vaultnote-{}.local", &vault_id[..8]);
    let CertifiedKey { cert, signing_key } =
        generate_simple_self_signed(vec![dns_name.clone(), "localhost".to_owned()])?;
    let certificate_sha256 = sha256_hex(cert.der().as_ref());
    let config = RelayConfig {
        protocol_version: PROTOCOL_VERSION,
        vault_id,
        created_at_epoch_millis: now_epoch_millis()?,
        authentication_token_sha256: sha256_hex(authentication_token.as_bytes()),
        key_derivation: KeyDerivationConfig {
            algorithm: "PBKDF2-HMAC-SHA256".to_owned(),
            iterations: KDF_ITERATIONS,
            salt: URL_SAFE_NO_PAD.encode(salt),
            key_bits: 256,
        },
        tls: TlsIdentityConfig {
            dns_name,
            certificate_sha256,
        },
    };
    salt.fill(0);
    config.validate()?;
    let attachments_directory = config.attachments_directory(data_directory);
    let certificate_path = data_directory.join(TLS_CERTIFICATE_FILENAME);
    let private_key_path = data_directory.join(TLS_PRIVATE_KEY_FILENAME);
    if certificate_path.exists() || private_key_path.exists() || attachments_directory.exists() {
        return Err(RelayError::AlreadyInitialized);
    }
    write_private_file_atomically(&certificate_path, cert.pem().as_bytes())?;
    if let Err(error) =
        write_private_file_atomically(&private_key_path, signing_key.serialize_pem().as_bytes())
    {
        let _ = fs::remove_file(&certificate_path);
        return Err(error);
    }
    let setup_result = (|| {
        fs::create_dir_all(&attachments_directory)?;
        set_private_directory_permissions(&attachments_directory)?;
        write_config_atomically(&config_path, &config, false)?;
        Ok(())
    })();
    if let Err(error) = setup_result {
        let _ = fs::remove_file(&config_path);
        let _ = fs::remove_file(&private_key_path);
        let _ = fs::remove_file(&certificate_path);
        let _ = fs::remove_dir(&attachments_directory);
        return Err(error);
    }
    Ok(InitializedRelay {
        config,
        authentication_token,
    })
}

pub fn rotate_authentication_token(data_directory: &Path) -> Result<Zeroizing<String>, RelayError> {
    let mut config = load_config(data_directory)?;
    verify_tls_identity(data_directory, &config)?;
    let authentication_token = generate_authentication_token();
    config.authentication_token_sha256 = sha256_hex(authentication_token.as_bytes());
    config.validate()?;
    write_config_atomically(&data_directory.join(CONFIG_FILENAME), &config, true)?;
    Ok(authentication_token)
}

pub fn tls_certificate_path(data_directory: &Path) -> PathBuf {
    data_directory.join(TLS_CERTIFICATE_FILENAME)
}

pub fn tls_private_key_path(data_directory: &Path) -> PathBuf {
    data_directory.join(TLS_PRIVATE_KEY_FILENAME)
}

pub fn verify_tls_identity(data_directory: &Path, config: &RelayConfig) -> Result<(), RelayError> {
    let certificate_path = tls_certificate_path(data_directory);
    let mut reader = BufReader::new(File::open(certificate_path)?);
    let certificates = rustls_pemfile::certs(&mut reader)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| RelayError::InvalidConfiguration)?;
    if certificates.len() != 1
        || sha256_hex(certificates[0].as_ref()) != config.tls.certificate_sha256
    {
        return Err(RelayError::InvalidConfiguration);
    }
    let private_key_metadata = fs::metadata(tls_private_key_path(data_directory))?;
    if !private_key_metadata.is_file() || private_key_metadata.len() == 0 {
        return Err(RelayError::InvalidConfiguration);
    }
    Ok(())
}

pub fn load_config(data_directory: &Path) -> Result<RelayConfig, RelayError> {
    validate_data_directory(data_directory)?;
    let path = data_directory.join(CONFIG_FILENAME);
    let file = File::open(path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            RelayError::NotInitialized
        } else {
            RelayError::Io(error)
        }
    })?;
    if file.metadata()?.len() > CONFIG_LIMIT_BYTES {
        return Err(RelayError::InvalidConfiguration);
    }
    let mut bytes = Vec::new();
    file.take(CONFIG_LIMIT_BYTES + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > CONFIG_LIMIT_BYTES {
        return Err(RelayError::InvalidConfiguration);
    }
    let config: RelayConfig =
        serde_json::from_slice(&bytes).map_err(|_| RelayError::InvalidConfiguration)?;
    config.validate()?;
    Ok(config)
}

fn write_config_atomically(
    path: &Path,
    config: &RelayConfig,
    replace_existing: bool,
) -> Result<(), RelayError> {
    let parent = path.parent().ok_or(RelayError::InvalidStoragePath)?;
    let temporary = parent.join(format!(".relay-config-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        set_private_file_creation_mode(&mut options);
        let mut file = options.open(&temporary)?;
        serde_json::to_writer_pretty(&mut file, config)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        if replace_existing {
            replace_file_atomically(&temporary, path)?;
        } else {
            if path.exists() {
                return Err(RelayError::AlreadyInitialized);
            }
            fs::rename(&temporary, path)?;
        }
        sync_directory(parent)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn generate_authentication_token() -> Zeroizing<String> {
    let mut token_bytes = [0_u8; 32];
    OsRng.fill_bytes(&mut token_bytes);
    let token = Zeroizing::new(format!("vns_{}", URL_SAFE_NO_PAD.encode(token_bytes)));
    token_bytes.fill(0);
    token
}

#[cfg(unix)]
fn replace_file_atomically(source: &Path, destination: &Path) -> Result<(), RelayError> {
    fs::rename(source, destination)?;
    Ok(())
}

#[cfg(not(unix))]
fn replace_file_atomically(source: &Path, destination: &Path) -> Result<(), RelayError> {
    let parent = destination.parent().ok_or(RelayError::InvalidStoragePath)?;
    let backup = parent.join(format!(".relay-config-{}.backup", uuid::Uuid::new_v4()));
    fs::rename(destination, &backup)?;
    if let Err(error) = fs::rename(source, destination) {
        let _ = fs::rename(&backup, destination);
        return Err(RelayError::Io(error));
    }
    let _ = fs::remove_file(backup);
    Ok(())
}

fn write_private_file_atomically(path: &Path, contents: &[u8]) -> Result<(), RelayError> {
    let parent = path.parent().ok_or(RelayError::InvalidStoragePath)?;
    if path.exists() {
        return Err(RelayError::AlreadyInitialized);
    }
    let temporary = parent.join(format!(".relay-secret-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        set_private_file_creation_mode(&mut options);
        let mut file = options.open(&temporary)?;
        file.write_all(contents)?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        sync_directory(parent)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn validate_data_directory(path: &Path) -> Result<(), RelayError> {
    if path.as_os_str().is_empty() || !path.is_absolute() {
        return Err(RelayError::InvalidStoragePath);
    }
    Ok(())
}

fn now_epoch_millis() -> Result<i64, RelayError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| RelayError::Clock)?;
    i64::try_from(duration.as_millis()).map_err(|_| RelayError::Clock)
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

pub fn is_lower_hex_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

pub fn is_valid_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
}

fn is_valid_local_dns_name(value: &str) -> bool {
    value.len() <= 253
        && value.ends_with(".local")
        && value.strip_suffix(".local").is_some_and(is_valid_id)
}

#[cfg(unix)]
fn set_private_file_creation_mode(options: &mut OpenOptions) {
    use std::os::unix::fs::OpenOptionsExt;
    options.mode(0o600);
}

#[cfg(not(unix))]
fn set_private_file_creation_mode(_: &mut OpenOptions) {}

#[cfg(unix)]
fn set_private_directory_permissions(path: &Path) -> Result<(), RelayError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

#[cfg(not(unix))]
fn set_private_directory_permissions(_: &Path) -> Result<(), RelayError> {
    Ok(())
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), RelayError> {
    File::open(path)?.sync_all()?;
    Ok(())
}

#[cfg(not(unix))]
fn sync_directory(_: &Path) -> Result<(), RelayError> {
    Ok(())
}
