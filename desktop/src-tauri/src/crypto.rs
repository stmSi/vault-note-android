use std::{
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::Arc,
};

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{AeadInPlace, KeyInit},
};
use hkdf::Hkdf;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use uuid::Uuid;
use zeroize::Zeroizing;

use crate::{error::AppError, vault_key::MasterKey};

const MAGIC: &[u8; 4] = b"VND1";
const FORMAT_VERSION: u8 = 1;
const KEY_VERSION: u32 = 1;
const CHUNK_BYTES: usize = 64 * 1024;
const HEADER_BYTES: usize = 4 + 1 + 4 + 4 + 8 + 8;
const TAG_BYTES: usize = 16;
const MAX_FILE_BYTES: u64 = 100 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct EncryptedAttachment {
    pub relative_path: String,
    pub display_name: String,
    pub mime_type: String,
    pub plaintext_size: i64,
    pub sha256: String,
}

#[derive(Clone)]
pub struct AttachmentCrypto {
    master_key: Option<Arc<MasterKey>>,
    directory: PathBuf,
}

pub struct StagedRemoval {
    original: PathBuf,
    staged: PathBuf,
    directory: PathBuf,
    finished: bool,
}

impl StagedRemoval {
    pub fn commit(mut self) -> Result<(), AppError> {
        fs::remove_file(&self.staged)?;
        sync_directory(&self.directory)?;
        self.finished = true;
        Ok(())
    }

    pub fn rollback(mut self) -> Result<(), AppError> {
        fs::rename(&self.staged, &self.original)?;
        sync_directory(&self.directory)?;
        self.finished = true;
        Ok(())
    }
}

impl Drop for StagedRemoval {
    fn drop(&mut self) {
        if !self.finished && self.staged.exists() && !self.original.exists() {
            let _ = fs::rename(&self.staged, &self.original);
            let _ = sync_directory(&self.directory);
        }
    }
}

impl AttachmentCrypto {
    pub fn new(master_key: Arc<MasterKey>, app_data_directory: &Path) -> Result<Self, AppError> {
        let directory = app_data_directory.join("attachments");
        fs::create_dir_all(&directory)?;
        harden_directory(&directory)?;
        Ok(Self {
            master_key: Some(master_key),
            directory,
        })
    }

    pub fn new_unencrypted(app_data_directory: &Path) -> Result<Self, AppError> {
        let directory = app_data_directory.join("attachments");
        fs::create_dir_all(&directory)?;
        harden_directory(&directory)?;
        Ok(Self {
            master_key: None,
            directory,
        })
    }

    pub fn is_encrypted(&self) -> bool {
        self.master_key.is_some()
    }

    pub fn encrypt_import(
        &self,
        source: &Path,
        attachment_id: &str,
    ) -> Result<EncryptedAttachment, AppError> {
        let metadata = source.metadata()?;
        if !metadata.is_file() {
            return Err(AppError::UnsupportedFile);
        }
        if metadata.len() > MAX_FILE_BYTES {
            return Err(AppError::FileTooLarge);
        }
        let display_name = validated_filename(source)?;
        let mime_type = mime_for_filename(&display_name)?;
        let final_name = self.relative_name(attachment_id);
        let final_path = self.directory.join(&final_name);
        if final_path.exists() {
            return Err(AppError::InvalidState);
        }
        let pending_path = self
            .directory
            .join(format!(".pending-{}.tmp", Uuid::new_v4().hyphenated()));
        let details = PendingAttachment {
            attachment_id,
            plaintext_size: metadata.len(),
            display_name,
            mime_type,
            relative_path: final_name,
        };
        let result = if self.is_encrypted() {
            self.encrypt_to_pending(source, &pending_path, details)
        } else {
            let mut input = File::open(source)?;
            self.store_plaintext_to_pending(&mut input, &pending_path, details)
        };
        if result.is_err() {
            let _ = fs::remove_file(&pending_path);
        }
        let attachment = result?;
        fs::rename(&pending_path, &final_path)?;
        sync_directory(&self.directory)?;
        harden_file(&final_path)?;
        Ok(attachment)
    }

    pub fn export_to(
        &self,
        relative_path: &str,
        attachment_id: &str,
        destination: &Path,
    ) -> Result<(), AppError> {
        self.validate_relative_path(relative_path, attachment_id)?;
        if destination.exists() {
            return Err(AppError::InvalidState);
        }
        let source = self.directory.join(relative_path);
        let parent = destination.parent().ok_or_else(|| {
            AppError::Storage(std::io::Error::other("destination directory unavailable"))
        })?;
        let pending = parent.join(format!(
            ".vaultnote-export-{}.tmp",
            Uuid::new_v4().hyphenated()
        ));
        let result = (|| {
            let mut output = File::options()
                .write(true)
                .create_new(true)
                .open(&pending)?;
            let mut source_file = File::open(&source)?;
            if let Some(master_key) = &self.master_key {
                decrypt_pass(&mut source_file, attachment_id, master_key.as_bytes(), None)?;
                source_file.seek(SeekFrom::Start(0))?;
                decrypt_pass(
                    &mut source_file,
                    attachment_id,
                    master_key.as_bytes(),
                    Some(&mut output as &mut dyn Write),
                )?;
            } else {
                copy_bounded(&mut source_file, &mut output, MAX_FILE_BYTES)?;
            }
            output.flush()?;
            output.sync_all()?;
            drop(output);
            fs::rename(&pending, destination)?;
            sync_directory(parent)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&pending);
        }
        result
    }

    pub fn decrypt_bytes(
        &self,
        relative_path: &str,
        attachment_id: &str,
    ) -> Result<Zeroizing<Vec<u8>>, AppError> {
        self.validate_relative_path(relative_path, attachment_id)?;
        let mut source = File::open(self.directory.join(relative_path))?;
        let mut plaintext = Zeroizing::new(Vec::new());
        if let Some(master_key) = &self.master_key {
            decrypt_pass(&mut source, attachment_id, master_key.as_bytes(), None)?;
            source.seek(SeekFrom::Start(0))?;
            decrypt_pass(
                &mut source,
                attachment_id,
                master_key.as_bytes(),
                Some(&mut *plaintext as &mut dyn Write),
            )?;
        } else {
            copy_bounded(&mut source, &mut *plaintext, MAX_FILE_BYTES)?;
        }
        Ok(plaintext)
    }

    pub fn encrypt_restored(
        &self,
        plaintext: &[u8],
        display_name: &str,
        mime_type: &str,
        attachment_id: &str,
    ) -> Result<EncryptedAttachment, AppError> {
        if plaintext.len() as u64 > MAX_FILE_BYTES {
            return Err(AppError::FileTooLarge);
        }
        let display_name = validated_display_name(display_name)?;
        let canonical_mime = mime_for_filename(&display_name)?;
        if canonical_mime != mime_type {
            return Err(AppError::UnsupportedFile);
        }
        let final_name = self.relative_name(attachment_id);
        let final_path = self.directory.join(&final_name);
        if final_path.exists() {
            return Err(AppError::InvalidState);
        }
        let pending_path = self
            .directory
            .join(format!(".pending-{}.tmp", Uuid::new_v4().hyphenated()));
        let details = PendingAttachment {
            attachment_id,
            plaintext_size: plaintext.len() as u64,
            display_name,
            mime_type: canonical_mime,
            relative_path: final_name,
        };
        let mut input = std::io::Cursor::new(plaintext);
        let result = if self.is_encrypted() {
            self.encrypt_reader_to_pending(&mut input, &pending_path, details)
        } else {
            self.store_plaintext_to_pending(&mut input, &pending_path, details)
        };
        if result.is_err() {
            let _ = fs::remove_file(&pending_path);
        }
        let attachment = result?;
        fs::rename(&pending_path, &final_path)?;
        sync_directory(&self.directory)?;
        harden_file(&final_path)?;
        Ok(attachment)
    }

    pub fn remove(&self, relative_path: &str, attachment_id: &str) -> Result<(), AppError> {
        self.validate_relative_path(relative_path, attachment_id)?;
        let path = self.directory.join(relative_path);
        match fs::remove_file(path) {
            Ok(()) => sync_directory(&self.directory),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(AppError::Storage(error)),
        }
    }

    pub fn stage_removal(
        &self,
        relative_path: &str,
        attachment_id: &str,
    ) -> Result<StagedRemoval, AppError> {
        self.validate_relative_path(relative_path, attachment_id)?;
        let original = self.directory.join(relative_path);
        let staged = self
            .directory
            .join(format!(".delete-{}.tmp", Uuid::new_v4().hyphenated()));
        fs::rename(&original, &staged)?;
        sync_directory(&self.directory)?;
        Ok(StagedRemoval {
            original,
            staged,
            directory: self.directory.clone(),
            finished: false,
        })
    }

    fn encrypt_to_pending(
        &self,
        source: &Path,
        pending: &Path,
        details: PendingAttachment<'_>,
    ) -> Result<EncryptedAttachment, AppError> {
        let mut input = File::open(source)?;
        self.encrypt_reader_to_pending(&mut input, pending, details)
    }

    fn encrypt_reader_to_pending(
        &self,
        input: &mut impl Read,
        pending: &Path,
        details: PendingAttachment<'_>,
    ) -> Result<EncryptedAttachment, AppError> {
        let master_key = self.master_key.as_ref().ok_or(AppError::InvalidState)?;
        let key = derive_attachment_key(master_key.as_bytes(), details.attachment_id)?;
        let cipher =
            Aes256Gcm::new_from_slice(key.as_slice()).map_err(|_| AppError::Cryptography)?;
        let mut nonce_prefix = [0_u8; 8];
        OsRng.fill_bytes(&mut nonce_prefix);
        let header = header(details.plaintext_size, nonce_prefix);
        let mut output = File::options().write(true).create_new(true).open(pending)?;
        output.write_all(&header)?;
        let chunks = chunk_count(details.plaintext_size);
        let mut digest = Sha256::new();
        let mut buffer = vec![0_u8; CHUNK_BYTES];
        for index in 0..chunks {
            let expected = chunk_plaintext_size(details.plaintext_size, index, chunks);
            input.read_exact(&mut buffer[..expected])?;
            digest.update(&buffer[..expected]);
            let nonce_bytes = nonce(nonce_prefix, index)?;
            let aad = additional_data(&header, details.attachment_id, index, index + 1 == chunks);
            let tag = cipher
                .encrypt_in_place_detached(
                    Nonce::from_slice(&nonce_bytes),
                    &aad,
                    &mut buffer[..expected],
                )
                .map_err(|_| AppError::Cryptography)?;
            output.write_all(&buffer[..expected])?;
            output.write_all(tag.as_slice())?;
        }
        let mut trailing = [0_u8; 1];
        if input.read(&mut trailing)? != 0 {
            return Err(AppError::InvalidState);
        }
        output.flush()?;
        output.sync_all()?;
        Ok(EncryptedAttachment {
            relative_path: details.relative_path,
            display_name: details.display_name,
            mime_type: details.mime_type,
            plaintext_size: i64::try_from(details.plaintext_size)
                .map_err(|_| AppError::FileTooLarge)?,
            sha256: hex_digest(digest.finalize().as_slice()),
        })
    }

    fn store_plaintext_to_pending(
        &self,
        input: &mut impl Read,
        pending: &Path,
        details: PendingAttachment<'_>,
    ) -> Result<EncryptedAttachment, AppError> {
        if self.is_encrypted() {
            return Err(AppError::InvalidState);
        }
        let mut output = File::options().write(true).create_new(true).open(pending)?;
        let mut digest = Sha256::new();
        let mut copied = 0_u64;
        let mut buffer = vec![0_u8; CHUNK_BYTES];
        loop {
            let read = input.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            copied = copied
                .checked_add(read as u64)
                .ok_or(AppError::FileTooLarge)?;
            if copied > MAX_FILE_BYTES || copied > details.plaintext_size {
                return Err(AppError::FileTooLarge);
            }
            digest.update(&buffer[..read]);
            output.write_all(&buffer[..read])?;
        }
        if copied != details.plaintext_size {
            return Err(AppError::InvalidState);
        }
        output.flush()?;
        output.sync_all()?;
        Ok(EncryptedAttachment {
            relative_path: details.relative_path,
            display_name: details.display_name,
            mime_type: details.mime_type,
            plaintext_size: i64::try_from(copied).map_err(|_| AppError::FileTooLarge)?,
            sha256: hex_digest(digest.finalize().as_slice()),
        })
    }

    fn relative_name(&self, attachment_id: &str) -> String {
        let extension = if self.is_encrypted() { "vne" } else { "vnp" };
        format!("{attachment_id}.{extension}")
    }

    fn validate_relative_path(
        &self,
        relative_path: &str,
        attachment_id: &str,
    ) -> Result<(), AppError> {
        if relative_path == self.relative_name(attachment_id) {
            Ok(())
        } else {
            Err(AppError::InvalidState)
        }
    }
}

struct PendingAttachment<'a> {
    attachment_id: &'a str,
    plaintext_size: u64,
    display_name: String,
    mime_type: String,
    relative_path: String,
}

fn decrypt_pass(
    source: &mut File,
    attachment_id: &str,
    master_key: &[u8; 32],
    mut output: Option<&mut dyn Write>,
) -> Result<(), AppError> {
    let mut header = [0_u8; HEADER_BYTES];
    source.read_exact(&mut header)?;
    let (plaintext_size, nonce_prefix) = parse_header(&header)?;
    let expected_file_size = HEADER_BYTES as u64
        + plaintext_size
        + chunk_count(plaintext_size)
            .checked_mul(TAG_BYTES as u64)
            .ok_or(AppError::Cryptography)?;
    if source.metadata()?.len() != expected_file_size {
        return Err(AppError::Cryptography);
    }
    let key = derive_attachment_key(master_key, attachment_id)?;
    let cipher = Aes256Gcm::new_from_slice(key.as_slice()).map_err(|_| AppError::Cryptography)?;
    let chunks = chunk_count(plaintext_size);
    let mut buffer = vec![0_u8; CHUNK_BYTES];
    let mut tag = [0_u8; TAG_BYTES];
    for index in 0..chunks {
        let expected = chunk_plaintext_size(plaintext_size, index, chunks);
        source.read_exact(&mut buffer[..expected])?;
        source.read_exact(&mut tag)?;
        let nonce_bytes = nonce(nonce_prefix, index)?;
        let aad = additional_data(&header, attachment_id, index, index + 1 == chunks);
        cipher
            .decrypt_in_place_detached(
                Nonce::from_slice(&nonce_bytes),
                &aad,
                &mut buffer[..expected],
                (&tag).into(),
            )
            .map_err(|_| AppError::Cryptography)?;
        if let Some(destination) = output.as_deref_mut() {
            destination.write_all(&buffer[..expected])?;
        }
    }
    Ok(())
}

fn derive_attachment_key(
    master_key: &[u8; 32],
    attachment_id: &str,
) -> Result<Zeroizing<[u8; 32]>, AppError> {
    let hkdf = Hkdf::<Sha256>::new(Some(b"VaultNote attachment key v1"), master_key);
    let mut key = Zeroizing::new([0_u8; 32]);
    hkdf.expand(attachment_id.as_bytes(), key.as_mut())
        .map_err(|_| AppError::Cryptography)?;
    Ok(key)
}

fn header(plaintext_size: u64, nonce_prefix: [u8; 8]) -> [u8; HEADER_BYTES] {
    let mut header = [0_u8; HEADER_BYTES];
    header[..4].copy_from_slice(MAGIC);
    header[4] = FORMAT_VERSION;
    header[5..9].copy_from_slice(&KEY_VERSION.to_be_bytes());
    header[9..13].copy_from_slice(&(CHUNK_BYTES as u32).to_be_bytes());
    header[13..21].copy_from_slice(&plaintext_size.to_be_bytes());
    header[21..29].copy_from_slice(&nonce_prefix);
    header
}

fn parse_header(header: &[u8; HEADER_BYTES]) -> Result<(u64, [u8; 8]), AppError> {
    if &header[..4] != MAGIC || header[4] != FORMAT_VERSION {
        return Err(AppError::Cryptography);
    }
    let key_version = u32::from_be_bytes(
        header[5..9]
            .try_into()
            .map_err(|_| AppError::Cryptography)?,
    );
    let chunk_size = u32::from_be_bytes(
        header[9..13]
            .try_into()
            .map_err(|_| AppError::Cryptography)?,
    );
    let plaintext_size = u64::from_be_bytes(
        header[13..21]
            .try_into()
            .map_err(|_| AppError::Cryptography)?,
    );
    if key_version != KEY_VERSION
        || chunk_size != CHUNK_BYTES as u32
        || plaintext_size > MAX_FILE_BYTES
    {
        return Err(AppError::Cryptography);
    }
    let prefix = header[21..29]
        .try_into()
        .map_err(|_| AppError::Cryptography)?;
    Ok((plaintext_size, prefix))
}

fn nonce(prefix: [u8; 8], index: u64) -> Result<[u8; 12], AppError> {
    let counter = u32::try_from(index).map_err(|_| AppError::Cryptography)?;
    let mut nonce = [0_u8; 12];
    nonce[..8].copy_from_slice(&prefix);
    nonce[8..].copy_from_slice(&counter.to_be_bytes());
    Ok(nonce)
}

fn additional_data(
    header: &[u8; HEADER_BYTES],
    attachment_id: &str,
    index: u64,
    final_chunk: bool,
) -> Vec<u8> {
    let mut aad = Vec::with_capacity(HEADER_BYTES + attachment_id.len() + 13);
    aad.extend_from_slice(header);
    aad.extend_from_slice(b"VNC2");
    aad.extend_from_slice(&(attachment_id.len() as u16).to_be_bytes());
    aad.extend_from_slice(attachment_id.as_bytes());
    aad.extend_from_slice(&index.to_be_bytes());
    aad.push(u8::from(final_chunk));
    aad
}

fn chunk_count(plaintext_size: u64) -> u64 {
    plaintext_size.div_ceil(CHUNK_BYTES as u64).max(1)
}

fn chunk_plaintext_size(plaintext_size: u64, index: u64, chunks: u64) -> usize {
    if index + 1 < chunks {
        CHUNK_BYTES
    } else {
        (plaintext_size - index * CHUNK_BYTES as u64) as usize
    }
}

fn validated_filename(source: &Path) -> Result<String, AppError> {
    let filename = source
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or(AppError::UnsupportedFile)?;
    validated_display_name(filename)
}

fn validated_display_name(filename: &str) -> Result<String, AppError> {
    let trimmed = filename.trim();
    if trimmed.is_empty()
        || trimmed.chars().count() > 255
        || trimmed.chars().any(char::is_control)
        || trimmed.contains(['/', '\\'])
    {
        return Err(AppError::UnsupportedFile);
    }
    Ok(trimmed.to_owned())
}

fn mime_for_filename(filename: &str) -> Result<String, AppError> {
    let extension = Path::new(filename)
        .extension()
        .and_then(|value| value.to_str())
        .map(str::to_ascii_lowercase)
        .ok_or(AppError::UnsupportedFile)?;
    let mime = match extension.as_str() {
        "txt" | "md" => "text/plain",
        "pdf" => "application/pdf",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "json" => "application/json",
        "csv" => "text/csv",
        "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        _ => return Err(AppError::UnsupportedFile),
    };
    Ok(mime.to_owned())
}

fn copy_bounded(
    source: &mut impl Read,
    destination: &mut impl Write,
    maximum: u64,
) -> Result<u64, AppError> {
    let mut copied = 0_u64;
    let mut buffer = vec![0_u8; CHUNK_BYTES];
    loop {
        let read = source.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        copied = copied
            .checked_add(read as u64)
            .ok_or(AppError::FileTooLarge)?;
        if copied > maximum {
            return Err(AppError::FileTooLarge);
        }
        destination.write_all(&buffer[..read])?;
    }
    Ok(copied)
}

fn hex_digest(bytes: &[u8]) -> String {
    let mut result = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write as _;
        write!(result, "{byte:02x}").expect("writing to a string cannot fail");
    }
    result
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

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use tempfile::tempdir;

    use super::*;

    #[test]
    fn attachment_round_trip_authenticates_before_export() {
        let directory = tempdir().expect("temporary directory should exist");
        let key = Arc::new(MasterKey::for_tests());
        let crypto =
            AttachmentCrypto::new(key, directory.path()).expect("crypto should initialize");
        let source = directory.path().join("source.txt");
        fs::write(&source, b"private attachment").expect("source should write");
        let id = "00000000-0000-0000-0000-000000000001";
        let encrypted = crypto
            .encrypt_import(&source, id)
            .expect("import should encrypt");
        let exported = directory.path().join("exported.txt");
        crypto
            .export_to(&encrypted.relative_path, id, &exported)
            .expect("export should decrypt");
        assert_eq!(fs::read(exported).unwrap(), b"private attachment");
        assert!(
            !fs::read(
                directory
                    .path()
                    .join("attachments")
                    .join(&encrypted.relative_path)
            )
            .unwrap()
            .windows(b"private attachment".len())
            .any(|window| window == b"private attachment")
        );
    }

    #[test]
    fn staged_removal_can_rollback_or_commit() {
        let directory = tempdir().expect("temporary directory should exist");
        let key = Arc::new(MasterKey::for_tests());
        let crypto =
            AttachmentCrypto::new(key, directory.path()).expect("crypto should initialize");
        let source = directory.path().join("source.txt");
        fs::write(&source, b"private attachment").expect("source should write");
        let id = "00000000-0000-0000-0000-000000000002";
        let encrypted = crypto
            .encrypt_import(&source, id)
            .expect("import should encrypt");

        crypto
            .stage_removal(&encrypted.relative_path, id)
            .expect("removal should stage")
            .rollback()
            .expect("rollback should restore ciphertext");
        assert!(
            directory
                .path()
                .join("attachments")
                .join(&encrypted.relative_path)
                .is_file()
        );

        crypto
            .stage_removal(&encrypted.relative_path, id)
            .expect("removal should stage")
            .commit()
            .expect("commit should delete ciphertext");
        assert!(
            !directory
                .path()
                .join("attachments")
                .join(&encrypted.relative_path)
                .exists()
        );
    }

    #[test]
    fn unencrypted_attachment_storage_preserves_plaintext() {
        let directory = tempdir().expect("temporary directory should exist");
        let storage =
            AttachmentCrypto::new_unencrypted(directory.path()).expect("storage should initialize");
        let source = directory.path().join("source.txt");
        fs::write(&source, b"plain attachment").expect("source should write");
        let id = "00000000-0000-0000-0000-000000000003";
        let stored = storage
            .encrypt_import(&source, id)
            .expect("import should store plaintext");
        assert_eq!(stored.relative_path, format!("{id}.vnp"));
        assert_eq!(
            fs::read(
                directory
                    .path()
                    .join("attachments")
                    .join(&stored.relative_path)
            )
            .expect("stored file should be readable"),
            b"plain attachment"
        );
        assert_eq!(
            &*storage
                .decrypt_bytes(&stored.relative_path, id)
                .expect("stored bytes should be readable"),
            b"plain attachment"
        );
    }
}
