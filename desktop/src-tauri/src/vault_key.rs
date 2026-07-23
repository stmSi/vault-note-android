use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};

use argon2::{Algorithm, Argon2, Params, Version};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use uuid::Uuid;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

use crate::{error::AppError, validation::validate_password};

const FORMAT_VERSION: u32 = 1;
const KDF_ALGORITHM: &str = "ARGON2ID-13";
const SALT_BYTES: usize = 16;
const KEY_BYTES: usize = 32;
const DERIVED_BYTES: usize = 64;
const MAX_METADATA_BYTES: u64 = 4 * 1024;
const DEFAULT_MEMORY_KIB: u32 = 64 * 1024;
const DEFAULT_ITERATIONS: u32 = 3;
const DEFAULT_PARALLELISM: u32 = 1;
const MIN_MEMORY_KIB: u32 = 8 * 1024;
const MAX_MEMORY_KIB: u32 = 256 * 1024;
const MIN_ITERATIONS: u32 = 2;
const MAX_ITERATIONS: u32 = 10;
const MAX_PARALLELISM: u32 = 4;

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct MasterKey([u8; KEY_BYTES]);

impl MasterKey {
    pub fn as_bytes(&self) -> &[u8; KEY_BYTES] {
        &self.0
    }

    #[cfg(test)]
    pub fn for_tests() -> Self {
        Self([0x5a; KEY_BYTES])
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct KeyMetadata {
    version: u32,
    kdf: String,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
    salt: String,
    verifier: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PlaintextMetadata {
    version: u32,
    mode: String,
}

#[derive(Clone)]
pub struct VaultKeyStore {
    path: PathBuf,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
}

#[derive(Clone)]
pub struct PlaintextVaultStore {
    path: PathBuf,
}

impl VaultKeyStore {
    pub fn new(app_data_directory: &Path) -> Self {
        Self {
            path: app_data_directory.join("vault-key.json"),
            memory_kib: DEFAULT_MEMORY_KIB,
            iterations: DEFAULT_ITERATIONS,
            parallelism: DEFAULT_PARALLELISM,
        }
    }

    pub fn is_configured(&self) -> bool {
        self.path.is_file()
    }

    pub fn initialize(&self, password: &str) -> Result<MasterKey, AppError> {
        validate_password(password)?;
        if self.path.exists() {
            return Err(AppError::InvalidState);
        }

        let mut salt = [0_u8; SALT_BYTES];
        OsRng.fill_bytes(&mut salt);
        let (key, verifier) = derive_key(
            password,
            &salt,
            self.memory_kib,
            self.iterations,
            self.parallelism,
        )?;
        let metadata = KeyMetadata {
            version: FORMAT_VERSION,
            kdf: KDF_ALGORITHM.to_owned(),
            memory_kib: self.memory_kib,
            iterations: self.iterations,
            parallelism: self.parallelism,
            salt: STANDARD.encode(salt),
            verifier: STANDARD.encode(verifier),
        };
        salt.zeroize();
        self.write_metadata(&metadata)?;
        Ok(key)
    }

    pub fn unlock(&self, password: &str) -> Result<MasterKey, AppError> {
        validate_password(password)?;
        let metadata = self.read_metadata()?;
        validate_metadata(&metadata)?;
        let mut salt = decode_fixed::<SALT_BYTES>(&metadata.salt)?;
        let expected_verifier = Zeroizing::new(decode_fixed::<KEY_BYTES>(&metadata.verifier)?);
        let result = derive_key(
            password,
            &salt,
            metadata.memory_kib,
            metadata.iterations,
            metadata.parallelism,
        );
        salt.zeroize();
        let (key, actual_verifier) = result?;
        if !bool::from(actual_verifier.ct_eq(expected_verifier.as_ref())) {
            return Err(AppError::InvalidCredentials);
        }
        Ok(key)
    }

    fn read_metadata(&self) -> Result<KeyMetadata, AppError> {
        let metadata = self
            .path
            .metadata()
            .map_err(|_| AppError::VaultConfiguration)?;
        if !metadata.is_file() || metadata.len() > MAX_METADATA_BYTES {
            return Err(AppError::VaultConfiguration);
        }
        let mut bytes = Zeroizing::new(Vec::with_capacity(metadata.len() as usize));
        File::open(&self.path)
            .and_then(|mut file| file.read_to_end(&mut bytes))
            .map_err(|_| AppError::VaultConfiguration)?;
        serde_json::from_slice(&bytes).map_err(|_| AppError::VaultConfiguration)
    }

    fn write_metadata(&self, metadata: &KeyMetadata) -> Result<(), AppError> {
        let encoded =
            Zeroizing::new(serde_json::to_vec(metadata).map_err(|_| AppError::VaultConfiguration)?);
        write_private_atomic(&self.path, &encoded)
    }

    #[cfg(test)]
    pub(crate) fn with_test_parameters(app_data_directory: &Path) -> Self {
        Self {
            path: app_data_directory.join("vault-key.json"),
            memory_kib: MIN_MEMORY_KIB,
            iterations: MIN_ITERATIONS,
            parallelism: 1,
        }
    }
}

impl PlaintextVaultStore {
    pub fn new(app_data_directory: &Path) -> Self {
        Self {
            path: app_data_directory.join("vault-plaintext.json"),
        }
    }

    pub fn is_configured(&self) -> bool {
        self.path.is_file()
    }

    pub fn initialize(&self) -> Result<(), AppError> {
        if self.path.exists() {
            return Err(AppError::InvalidState);
        }
        let metadata = PlaintextMetadata {
            version: FORMAT_VERSION,
            mode: "UNENCRYPTED".to_owned(),
        };
        let encoded = Zeroizing::new(
            serde_json::to_vec(&metadata).map_err(|_| AppError::VaultConfiguration)?,
        );
        write_private_atomic(&self.path, &encoded)
    }

    pub fn validate(&self) -> Result<(), AppError> {
        let metadata = self
            .path
            .metadata()
            .map_err(|_| AppError::VaultConfiguration)?;
        if !metadata.is_file() || metadata.len() > MAX_METADATA_BYTES {
            return Err(AppError::VaultConfiguration);
        }
        let bytes = Zeroizing::new(fs::read(&self.path).map_err(|_| AppError::VaultConfiguration)?);
        let marker: PlaintextMetadata =
            serde_json::from_slice(&bytes).map_err(|_| AppError::VaultConfiguration)?;
        if marker.version != FORMAT_VERSION || marker.mode != "UNENCRYPTED" {
            return Err(AppError::VaultConfiguration);
        }
        Ok(())
    }
}

fn write_private_atomic(path: &Path, contents: &[u8]) -> Result<(), AppError> {
    let parent = path.parent().ok_or_else(|| {
        AppError::Storage(std::io::Error::other(
            "vault metadata directory unavailable",
        ))
    })?;
    let temporary_path = parent.join(format!(
        ".vault-metadata-{}.tmp",
        Uuid::new_v4().hyphenated()
    ));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary_path)?;
        file.write_all(contents)?;
        file.sync_all()?;
        fs::hard_link(&temporary_path, path)?;
        fs::remove_file(&temporary_path)?;
        sync_directory(parent)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary_path);
    }
    result
}

fn validate_metadata(metadata: &KeyMetadata) -> Result<(), AppError> {
    if metadata.version != FORMAT_VERSION
        || metadata.kdf != KDF_ALGORITHM
        || !(MIN_MEMORY_KIB..=MAX_MEMORY_KIB).contains(&metadata.memory_kib)
        || !(MIN_ITERATIONS..=MAX_ITERATIONS).contains(&metadata.iterations)
        || !(1..=MAX_PARALLELISM).contains(&metadata.parallelism)
    {
        return Err(AppError::VaultConfiguration);
    }
    Ok(())
}

fn derive_key(
    password: &str,
    salt: &[u8; SALT_BYTES],
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
) -> Result<(MasterKey, [u8; KEY_BYTES]), AppError> {
    let params = Params::new(memory_kib, iterations, parallelism, Some(DERIVED_BYTES))
        .map_err(|_| AppError::VaultConfiguration)?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut material = Zeroizing::new([0_u8; DERIVED_BYTES]);
    argon2
        .hash_password_into(password.as_bytes(), salt, material.as_mut())
        .map_err(|_| AppError::Cryptography)?;

    let mut key = [0_u8; KEY_BYTES];
    key.copy_from_slice(&material[..KEY_BYTES]);
    let verifier: [u8; KEY_BYTES] = Sha256::digest(&material[KEY_BYTES..])
        .as_slice()
        .try_into()
        .map_err(|_| AppError::Cryptography)?;
    Ok((MasterKey(key), verifier))
}

fn decode_fixed<const N: usize>(encoded: &str) -> Result<[u8; N], AppError> {
    let decoded = Zeroizing::new(
        STANDARD
            .decode(encoded.as_bytes())
            .map_err(|_| AppError::VaultConfiguration)?,
    );
    decoded
        .as_slice()
        .try_into()
        .map_err(|_| AppError::VaultConfiguration)
}

#[cfg(unix)]
fn sync_directory(directory: &Path) -> Result<(), AppError> {
    File::open(directory)?.sync_all()?;
    Ok(())
}

#[cfg(not(unix))]
fn sync_directory(_directory: &Path) -> Result<(), AppError> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn initialized_password_unlocks_same_key_and_rejects_wrong_password() {
        let directory = tempdir().expect("temporary directory should exist");
        let store = VaultKeyStore::with_test_parameters(directory.path());
        let created = store
            .initialize("correct horse battery staple")
            .expect("key should initialize");
        let unlocked = store
            .unlock("correct horse battery staple")
            .expect("correct password should unlock");
        assert_eq!(created.as_bytes(), unlocked.as_bytes());
        assert!(matches!(
            store.unlock("incorrect password"),
            Err(AppError::InvalidCredentials)
        ));
        let contents = fs::read_to_string(&store.path).expect("metadata should be readable");
        assert!(!contents.contains("correct horse battery staple"));
    }

    #[cfg(unix)]
    #[test]
    fn metadata_file_is_private() {
        use std::os::unix::fs::PermissionsExt;

        let directory = tempdir().expect("temporary directory should exist");
        let store = VaultKeyStore::with_test_parameters(directory.path());
        store
            .initialize("correct horse battery staple")
            .expect("key should initialize");
        let mode = store
            .path
            .metadata()
            .expect("metadata should exist")
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(mode, 0o600);
    }
}
