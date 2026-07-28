use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
};

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use hkdf::Hkdf;
use openssl::symm::{Cipher, Crypter, Mode};
use pbkdf2::pbkdf2_hmac;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use uuid::Uuid;
use zeroize::{Zeroize, Zeroizing};

use crate::{error::AppError, sync_wire};

const MAGIC: &[u8; 4] = b"VNS3";
const ENVELOPE_VERSION: u8 = 1;
const KEY_VERSION: u16 = 1;
const HEADER_BYTES: usize = 4 + 1 + 1 + 2 + NONCE_BYTES + 8;
const NONCE_BYTES: usize = 12;
const TAG_BYTES: usize = 16;
const KEY_BYTES: usize = 32;
const BUFFER_BYTES: usize = 64 * 1024;
const AAD_PREFIX: &[u8] = b"VaultNote Sync Envelope";
const PBKDF2_ITERATIONS: u32 = 600_000;

pub const KEY_CHECK_OBJECT_ID: &str = "key-check";
pub const KEY_CHECK_PLAINTEXT: &[u8] = b"VaultNote Sync Key Check v3";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EnvelopePurpose {
    Item,
    Attachment,
    KeyCheck,
}

impl EnvelopePurpose {
    fn code(self) -> u8 {
        match self {
            Self::Item => 1,
            Self::Attachment => 2,
            Self::KeyCheck => 3,
        }
    }

    fn label(self) -> &'static [u8] {
        match self {
            Self::Item => b"VaultNote Sync v3 Item",
            Self::Attachment => b"VaultNote Sync v3 Attachment",
            Self::KeyCheck => b"VaultNote Sync v3 Key Check",
        }
    }

    fn from_code(value: u8) -> Result<Self, AppError> {
        match value {
            1 => Ok(Self::Item),
            2 => Ok(Self::Attachment),
            3 => Ok(Self::KeyCheck),
            _ => Err(AppError::Cryptography),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EnvelopeInfo {
    pub purpose: EnvelopePurpose,
    pub key_version: u16,
    pub plaintext_length: u64,
}

pub struct SyncMasterKey(Zeroizing<[u8; KEY_BYTES]>);

impl SyncMasterKey {
    pub fn derive(
        password: &str,
        salt_base64_url: &str,
        iterations: u32,
    ) -> Result<Self, AppError> {
        if !(8..=1_024).contains(&password.chars().count()) || iterations != PBKDF2_ITERATIONS {
            return Err(AppError::InvalidInput {
                field: "sync_password",
                reason: "invalid password or KDF parameters".to_owned(),
            });
        }
        let mut salt = Zeroizing::new(
            URL_SAFE_NO_PAD
                .decode(salt_base64_url)
                .map_err(|_| AppError::Cryptography)?,
        );
        if salt.len() != KEY_BYTES {
            return Err(AppError::Cryptography);
        }
        let mut key = Zeroizing::new([0_u8; KEY_BYTES]);
        pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt, iterations, key.as_mut());
        salt.zeroize();
        Ok(Self(key))
    }

    pub fn from_bytes(bytes: [u8; KEY_BYTES]) -> Self {
        Self(Zeroizing::new(bytes))
    }

    pub fn as_bytes(&self) -> &[u8; KEY_BYTES] {
        &self.0
    }
}

pub fn encrypt_bytes(
    master_key: &SyncMasterKey,
    vault_id: &str,
    object_id: &str,
    purpose: EnvelopePurpose,
    plaintext: &[u8],
) -> Result<Vec<u8>, AppError> {
    let mut nonce = [0_u8; NONCE_BYTES];
    OsRng.fill_bytes(&mut nonce);
    encrypt_bytes_with_nonce(master_key, vault_id, object_id, purpose, plaintext, nonce)
}

fn encrypt_bytes_with_nonce(
    master_key: &SyncMasterKey,
    vault_id: &str,
    object_id: &str,
    purpose: EnvelopePurpose,
    plaintext: &[u8],
    nonce: [u8; NONCE_BYTES],
) -> Result<Vec<u8>, AppError> {
    validate_context(vault_id, object_id)?;
    if plaintext.len() > sync_wire::MAX_ITEM_PLAINTEXT_BYTES {
        return Err(AppError::FileTooLarge);
    }
    let header = encode_header(purpose, nonce, plaintext.len() as u64);
    let key = derive_purpose_key(master_key, purpose)?;
    let cipher = Aes256Gcm::new_from_slice(key.as_slice()).map_err(|_| AppError::Cryptography)?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: &additional_data(&header, vault_id, object_id),
            },
        )
        .map_err(|_| AppError::Cryptography)?;
    let mut envelope = Vec::with_capacity(header.len() + ciphertext.len());
    envelope.extend_from_slice(&header);
    envelope.extend_from_slice(&ciphertext);
    Ok(envelope)
}

pub fn decrypt_bytes(
    master_key: &SyncMasterKey,
    vault_id: &str,
    object_id: &str,
    expected_purpose: EnvelopePurpose,
    envelope: &[u8],
) -> Result<Zeroizing<Vec<u8>>, AppError> {
    validate_context(vault_id, object_id)?;
    let (header, nonce, info) = parse_header(envelope)?;
    if info.purpose != expected_purpose
        || info.plaintext_length > sync_wire::MAX_ITEM_PLAINTEXT_BYTES as u64
    {
        return Err(AppError::Cryptography);
    }
    let key = derive_purpose_key(master_key, expected_purpose)?;
    let cipher = Aes256Gcm::new_from_slice(key.as_slice()).map_err(|_| AppError::Cryptography)?;
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &envelope[HEADER_BYTES..],
                aad: &additional_data(header, vault_id, object_id),
            },
        )
        .map_err(|_| AppError::Cryptography)?;
    if plaintext.len() as u64 != info.plaintext_length {
        return Err(AppError::Cryptography);
    }
    Ok(Zeroizing::new(plaintext))
}

pub fn encrypt_file_from_producer(
    master_key: &SyncMasterKey,
    vault_id: &str,
    object_id: &str,
    plaintext_length: u64,
    destination: &Path,
    producer: impl FnOnce(&mut dyn Write) -> Result<(), AppError>,
) -> Result<EnvelopeInfo, AppError> {
    validate_context(vault_id, object_id)?;
    if plaintext_length > sync_wire::MAX_ATTACHMENT_PLAINTEXT_BYTES || destination.exists() {
        return Err(AppError::InvalidState);
    }
    let parent = destination.parent().ok_or_else(|| {
        AppError::Storage(std::io::Error::other("sync transfer directory unavailable"))
    })?;
    fs::create_dir_all(parent)?;
    harden_directory(parent)?;
    let temporary = parent.join(format!(
        ".sync-envelope-{}.tmp",
        Uuid::new_v4().hyphenated()
    ));
    let result = (|| {
        let mut nonce = [0_u8; NONCE_BYTES];
        OsRng.fill_bytes(&mut nonce);
        let header = encode_header(EnvelopePurpose::Attachment, nonce, plaintext_length);
        let key = derive_purpose_key(master_key, EnvelopePurpose::Attachment)?;
        let mut crypter = Crypter::new(
            Cipher::aes_256_gcm(),
            Mode::Encrypt,
            key.as_slice(),
            Some(&nonce),
        )
        .map_err(|_| AppError::Cryptography)?;
        crypter.pad(false);
        crypter
            .aad_update(&additional_data(&header, vault_id, object_id))
            .map_err(|_| AppError::Cryptography)?;
        let mut output = private_file(&temporary)?;
        output.write_all(&header)?;
        {
            let mut encrypting = GcmEncryptingWriter {
                crypter: &mut crypter,
                destination: &mut output,
                plaintext_written: 0,
            };
            producer(&mut encrypting)?;
            if encrypting.plaintext_written != plaintext_length {
                return Err(AppError::Cryptography);
            }
        }
        let mut final_bytes = [0_u8; TAG_BYTES + 16];
        let final_length = crypter
            .finalize(&mut final_bytes)
            .map_err(|_| AppError::Cryptography)?;
        output.write_all(&final_bytes[..final_length])?;
        let mut tag = [0_u8; TAG_BYTES];
        crypter
            .get_tag(&mut tag)
            .map_err(|_| AppError::Cryptography)?;
        output.write_all(&tag)?;
        output.flush()?;
        output.sync_all()?;
        let expected_length = HEADER_BYTES as u64 + plaintext_length + TAG_BYTES as u64;
        if output.metadata()?.len() != expected_length {
            return Err(AppError::Cryptography);
        }
        drop(output);
        fs::rename(&temporary, destination)?;
        harden_file(destination)?;
        sync_directory(parent)?;
        Ok(EnvelopeInfo {
            purpose: EnvelopePurpose::Attachment,
            key_version: KEY_VERSION,
            plaintext_length,
        })
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

/// Authenticates the entire envelope before a second pass writes plaintext.
///
/// The destination must be controlled staging that is discarded if this call fails, because a
/// local attacker able to replace the envelope between passes is outside this method's boundary.
pub fn decrypt_file_verified_to(
    master_key: &SyncMasterKey,
    vault_id: &str,
    object_id: &str,
    source: &Path,
    expected_plaintext_length: u64,
    destination: &mut dyn Write,
) -> Result<EnvelopeInfo, AppError> {
    validate_context(vault_id, object_id)?;
    let mut input = File::open(source)?;
    let info = decrypt_file_pass(
        master_key,
        vault_id,
        object_id,
        &mut input,
        expected_plaintext_length,
        None,
    )?;
    input.seek(SeekFrom::Start(0))?;
    let second = decrypt_file_pass(
        master_key,
        vault_id,
        object_id,
        &mut input,
        expected_plaintext_length,
        Some(destination),
    )?;
    if info != second {
        return Err(AppError::Cryptography);
    }
    Ok(info)
}

pub fn inspect_file(source: &Path) -> Result<EnvelopeInfo, AppError> {
    let mut file = File::open(source)?;
    let mut header = [0_u8; HEADER_BYTES];
    file.read_exact(&mut header)?;
    let (_, _, info) = parse_header_with_length(&header, file.metadata()?.len())?;
    Ok(info)
}

pub fn sha256_file(source: &Path) -> Result<String, AppError> {
    let mut file = File::open(source)?;
    let mut digest = Sha256::new();
    let mut buffer = vec![0_u8; BUFFER_BYTES];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(lower_hex(&digest.finalize()))
}

pub fn sha256_bytes(source: &[u8]) -> String {
    lower_hex(&Sha256::digest(source))
}

fn decrypt_file_pass(
    master_key: &SyncMasterKey,
    vault_id: &str,
    object_id: &str,
    input: &mut File,
    expected_plaintext_length: u64,
    mut destination: Option<&mut dyn Write>,
) -> Result<EnvelopeInfo, AppError> {
    let file_length = input.metadata()?.len();
    let mut header = [0_u8; HEADER_BYTES];
    input.read_exact(&mut header)?;
    let (_, nonce, info) = parse_header_with_length(&header, file_length)?;
    if info.purpose != EnvelopePurpose::Attachment
        || info.plaintext_length != expected_plaintext_length
        || info.plaintext_length > sync_wire::MAX_ATTACHMENT_PLAINTEXT_BYTES
    {
        return Err(AppError::Cryptography);
    }
    let key = derive_purpose_key(master_key, EnvelopePurpose::Attachment)?;
    let mut crypter = Crypter::new(
        Cipher::aes_256_gcm(),
        Mode::Decrypt,
        key.as_slice(),
        Some(&nonce),
    )
    .map_err(|_| AppError::Cryptography)?;
    crypter.pad(false);
    crypter
        .aad_update(&additional_data(&header, vault_id, object_id))
        .map_err(|_| AppError::Cryptography)?;
    let mut remaining = info.plaintext_length;
    let mut buffer = vec![0_u8; BUFFER_BYTES];
    let mut decrypted = vec![0_u8; BUFFER_BYTES + 16];
    while remaining > 0 {
        let requested = remaining.min(buffer.len() as u64) as usize;
        input.read_exact(&mut buffer[..requested])?;
        remaining -= requested as u64;
        let produced = crypter
            .update(&buffer[..requested], &mut decrypted)
            .map_err(|_| AppError::Cryptography)?;
        if let Some(output) = &mut destination {
            output.write_all(&decrypted[..produced])?;
        }
        decrypted[..produced].zeroize();
    }
    let mut tag = [0_u8; TAG_BYTES];
    input.read_exact(&mut tag)?;
    crypter.set_tag(&tag).map_err(|_| AppError::Cryptography)?;
    let produced = crypter
        .finalize(&mut decrypted)
        .map_err(|_| AppError::Cryptography)?;
    if let Some(output) = &mut destination {
        output.write_all(&decrypted[..produced])?;
    }
    decrypted.zeroize();
    let mut trailing = [0_u8; 1];
    if input.read(&mut trailing)? != 0 {
        return Err(AppError::Cryptography);
    }
    Ok(info)
}

fn encode_header(
    purpose: EnvelopePurpose,
    nonce: [u8; NONCE_BYTES],
    plaintext_length: u64,
) -> [u8; HEADER_BYTES] {
    let mut header = [0_u8; HEADER_BYTES];
    header[..4].copy_from_slice(MAGIC);
    header[4] = ENVELOPE_VERSION;
    header[5] = purpose.code();
    header[6..8].copy_from_slice(&KEY_VERSION.to_be_bytes());
    header[8..20].copy_from_slice(&nonce);
    header[20..28].copy_from_slice(&plaintext_length.to_be_bytes());
    header
}

fn parse_header(envelope: &[u8]) -> Result<(&[u8], [u8; NONCE_BYTES], EnvelopeInfo), AppError> {
    if envelope.len() < HEADER_BYTES + TAG_BYTES {
        return Err(AppError::Cryptography);
    }
    parse_header_with_length(
        envelope[..HEADER_BYTES]
            .try_into()
            .map_err(|_| AppError::Cryptography)?,
        envelope.len() as u64,
    )
    .map(|(_, nonce, info)| (&envelope[..HEADER_BYTES], nonce, info))
}

fn parse_header_with_length(
    header: &[u8; HEADER_BYTES],
    envelope_length: u64,
) -> Result<(&[u8], [u8; NONCE_BYTES], EnvelopeInfo), AppError> {
    if &header[..4] != MAGIC || header[4] != ENVELOPE_VERSION {
        return Err(AppError::Cryptography);
    }
    let purpose = EnvelopePurpose::from_code(header[5])?;
    let key_version = u16::from_be_bytes(
        header[6..8]
            .try_into()
            .map_err(|_| AppError::Cryptography)?,
    );
    let nonce = header[8..20]
        .try_into()
        .map_err(|_| AppError::Cryptography)?;
    let plaintext_length = u64::from_be_bytes(
        header[20..28]
            .try_into()
            .map_err(|_| AppError::Cryptography)?,
    );
    if key_version != KEY_VERSION
        || envelope_length != HEADER_BYTES as u64 + plaintext_length + TAG_BYTES as u64
    {
        return Err(AppError::Cryptography);
    }
    Ok((
        header,
        nonce,
        EnvelopeInfo {
            purpose,
            key_version,
            plaintext_length,
        },
    ))
}

fn derive_purpose_key(
    master_key: &SyncMasterKey,
    purpose: EnvelopePurpose,
) -> Result<Zeroizing<[u8; KEY_BYTES]>, AppError> {
    let hkdf = Hkdf::<Sha256>::new(None, master_key.as_bytes());
    let mut output = Zeroizing::new([0_u8; KEY_BYTES]);
    hkdf.expand(purpose.label(), output.as_mut())
        .map_err(|_| AppError::Cryptography)?;
    Ok(output)
}

fn additional_data(header: &[u8], vault_id: &str, object_id: &str) -> Vec<u8> {
    let mut output =
        Vec::with_capacity(AAD_PREFIX.len() + header.len() + vault_id.len() + object_id.len() + 8);
    output.extend_from_slice(AAD_PREFIX);
    output.extend_from_slice(header);
    output.extend_from_slice(&(vault_id.len() as u32).to_be_bytes());
    output.extend_from_slice(vault_id.as_bytes());
    output.extend_from_slice(&(object_id.len() as u32).to_be_bytes());
    output.extend_from_slice(object_id.as_bytes());
    output
}

fn validate_context(vault_id: &str, object_id: &str) -> Result<(), AppError> {
    if sync_wire::valid_id(vault_id) && sync_wire::valid_id(object_id) {
        Ok(())
    } else {
        Err(AppError::Cryptography)
    }
}

struct GcmEncryptingWriter<'a> {
    crypter: &'a mut Crypter,
    destination: &'a mut File,
    plaintext_written: u64,
}

impl Write for GcmEncryptingWriter<'_> {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        let mut encrypted = vec![0_u8; buffer.len() + 16];
        let produced = self
            .crypter
            .update(buffer, &mut encrypted)
            .map_err(std::io::Error::other)?;
        self.destination.write_all(&encrypted[..produced])?;
        encrypted.zeroize();
        self.plaintext_written = self
            .plaintext_written
            .checked_add(buffer.len() as u64)
            .ok_or_else(|| std::io::Error::other("sync attachment is too large"))?;
        Ok(buffer.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.destination.flush()
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
fn harden_directory(path: &Path) -> Result<(), AppError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

#[cfg(not(unix))]
fn harden_directory(_path: &Path) -> Result<(), AppError> {
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

fn lower_hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        write!(output, "{byte:02x}").expect("writing to a string cannot fail");
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn master_key() -> SyncMasterKey {
        SyncMasterKey::from_bytes(std::array::from_fn(|index| (index + 1) as u8))
    }

    #[test]
    fn item_envelope_binds_vault_object_and_purpose() {
        let envelope = encrypt_bytes_with_nonce(
            &master_key(),
            "vault_test",
            "item_123",
            EnvelopePurpose::Item,
            b"Bangkok filename searchable",
            [0x11; NONCE_BYTES],
        )
        .expect("envelope should encrypt");
        let plaintext = decrypt_bytes(
            &master_key(),
            "vault_test",
            "item_123",
            EnvelopePurpose::Item,
            &envelope,
        )
        .expect("envelope should decrypt");
        assert_eq!(plaintext.as_slice(), b"Bangkok filename searchable");
        assert!(
            decrypt_bytes(
                &master_key(),
                "another_vault",
                "item_123",
                EnvelopePurpose::Item,
                &envelope,
            )
            .is_err()
        );
        assert!(
            decrypt_bytes(
                &master_key(),
                "vault_test",
                "item_456",
                EnvelopePurpose::Item,
                &envelope,
            )
            .is_err()
        );
        assert!(
            decrypt_bytes(
                &master_key(),
                "vault_test",
                "item_123",
                EnvelopePurpose::KeyCheck,
                &envelope,
            )
            .is_err()
        );
    }

    #[test]
    fn attachment_stream_round_trip_rejects_corruption_before_output() {
        let directory = tempdir().expect("temporary directory should exist");
        let encrypted = directory.path().join("attachment.bin");
        let plaintext = vec![0x5a; 192 * 1024 + 17];
        encrypt_file_from_producer(
            &master_key(),
            "vault_test",
            "attachment_123",
            plaintext.len() as u64,
            &encrypted,
            |destination| {
                destination.write_all(&plaintext)?;
                Ok(())
            },
        )
        .expect("attachment should encrypt");
        let mut restored = Vec::new();
        decrypt_file_verified_to(
            &master_key(),
            "vault_test",
            "attachment_123",
            &encrypted,
            plaintext.len() as u64,
            &mut restored,
        )
        .expect("attachment should decrypt");
        assert_eq!(restored, plaintext);

        let mut bytes = fs::read(&encrypted).expect("envelope should read");
        let last = bytes.len() - 1;
        bytes[last] ^= 1;
        fs::write(&encrypted, bytes).expect("corruption should write");
        let mut rejected = Vec::new();
        assert!(
            decrypt_file_verified_to(
                &master_key(),
                "vault_test",
                "attachment_123",
                &encrypted,
                plaintext.len() as u64,
                &mut rejected,
            )
            .is_err()
        );
        assert!(rejected.is_empty());
    }

    #[test]
    fn password_derivation_validates_protocol_parameters() {
        let salt = URL_SAFE_NO_PAD.encode([7_u8; KEY_BYTES]);
        let first = SyncMasterKey::derive("correct horse battery staple", &salt, 600_000)
            .expect("key should derive");
        let second = SyncMasterKey::derive("correct horse battery staple", &salt, 600_000)
            .expect("key should derive");
        assert_eq!(first.as_bytes(), second.as_bytes());
        assert!(SyncMasterKey::derive("short", &salt, 600_000).is_err());
        assert!(SyncMasterKey::derive("correct horse battery staple", &salt, 599_999).is_err());
    }
}
