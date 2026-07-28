use std::{
    collections::{HashMap, HashSet},
    fs::{self, File},
    io::{Read, Write},
    path::Path,
    sync::Arc,
};

use aes_gcm::{
    Aes256Gcm, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use pbkdf2::pbkdf2_hmac;
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use unicode_normalization::UnicodeNormalization;
use uuid::Uuid;
use zeroize::Zeroizing;
use zip::{CompressionMethod, ZipArchive, ZipWriter, write::SimpleFileOptions};

use crate::{
    crypto::AttachmentCrypto,
    error::AppError,
    models::{
        AttachmentRecord, BackupSummary, DatedEntry, DatedEntryAlert, NoteBodyDocument,
        PortableAttachment, PortableDatedEntry, PortableItem, PortableItemTag, PortableSnapshot,
        PortableTag, RestoreSummary, VaultAttachment,
    },
    repository::VaultRepository,
    validation::validate_note_document,
};

const MAGIC: &str = "VaultNoteBackup";
const FORMAT_VERSION: u32 = 1;
const MINIMUM_READER_VERSION: u32 = 1;
const PLAINTEXT_FORMAT_VERSION: u32 = 2;
const PLAINTEXT_MINIMUM_READER_VERSION: u32 = 2;
const DATABASE_SCHEMA_VERSION: u32 = 3;
const MINIMUM_DATABASE_SCHEMA_VERSION: u32 = 1;
const MANIFEST_PATH: &str = "manifest.json";
const CHECKSUMS_PATH: &str = "checksums.json.enc";
const DATABASE_PATH: &str = "database.json.enc";
const PLAINTEXT_CHECKSUMS_PATH: &str = "checksums.json";
const PLAINTEXT_DATABASE_PATH: &str = "database.json";
const KDF_ALGORITHM: &str = "PBKDF2-HMAC-SHA256";
const CIPHER_ALGORITHM: &str = "AES-256-GCM";
const KDF_ITERATIONS: u32 = 600_000;
const MAX_ARCHIVE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_ENTRY_BYTES: u64 = 128 * 1024 * 1024;
const MAX_DATABASE_BYTES: usize = 128 * 1024 * 1024;
const MAX_ATTACHMENTS: usize = 100_000;
const MAX_ITEMS: usize = 1_000_000;
const ENTRY_HEADER_BYTES: usize = 18;

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BackupProtection {
    Encrypted,
    Plaintext,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BackupInspection {
    pub protection: BackupProtection,
    pub created_at_epoch_millis: i64,
}

#[derive(Clone, Copy)]
enum RestoreEntryProtection<'a> {
    Encrypted {
        key: &'a Zeroizing<[u8; 32]>,
        binding: &'a [u8],
    },
    Plaintext,
}

#[derive(Clone)]
pub struct BackupService {
    repository: Arc<dyn VaultRepository>,
    crypto: AttachmentCrypto,
}

impl BackupService {
    pub fn new(repository: Arc<dyn VaultRepository>, crypto: AttachmentCrypto) -> Self {
        Self { repository, crypto }
    }

    pub fn export_to(
        &self,
        password: &str,
        now: i64,
        mut destination: std::path::PathBuf,
    ) -> Result<BackupSummary, AppError> {
        validate_password(password)?;
        if destination.extension().is_none() {
            destination.set_extension("vnb");
        }
        if destination.exists() {
            return Err(AppError::InvalidState);
        }
        let snapshot = self.repository.portable_snapshot()?;
        if snapshot.items.len() > MAX_ITEMS || snapshot.attachments.len() > MAX_ATTACHMENTS {
            return Err(AppError::BackupTooLarge);
        }
        let summary = BackupSummary {
            item_count: snapshot.items.len(),
            attachment_count: snapshot.attachments.len(),
            created_at_epoch_millis: now,
        };
        self.write_archive(&destination, password, now, snapshot)?;
        Ok(summary)
    }

    pub fn restore_from(&self, password: &str, source: &Path) -> Result<RestoreSummary, AppError> {
        validate_password(password)?;
        let metadata = source.metadata()?;
        if !metadata.is_file() || metadata.len() > MAX_ARCHIVE_BYTES {
            return Err(AppError::BackupTooLarge);
        }
        self.restore_archive(source, password)
    }

    pub fn export_plaintext_to(
        &self,
        now: i64,
        mut destination: std::path::PathBuf,
    ) -> Result<BackupSummary, AppError> {
        if destination.extension().is_none() {
            destination.set_extension("vnb");
        }
        if destination.exists() {
            return Err(AppError::InvalidState);
        }
        let snapshot = self.repository.portable_snapshot()?;
        if snapshot.items.len() > MAX_ITEMS || snapshot.attachments.len() > MAX_ATTACHMENTS {
            return Err(AppError::BackupTooLarge);
        }
        let summary = BackupSummary {
            item_count: snapshot.items.len(),
            attachment_count: snapshot.attachments.len(),
            created_at_epoch_millis: now,
        };
        self.write_plaintext_archive(&destination, now, snapshot)?;
        Ok(summary)
    }

    pub fn inspect(&self, source: &Path) -> Result<BackupInspection, AppError> {
        let metadata = source.metadata()?;
        if !metadata.is_file() || metadata.len() > MAX_ARCHIVE_BYTES {
            return Err(AppError::BackupTooLarge);
        }
        let mut archive = ZipArchive::new(File::open(source)?).map_err(zip_invalid)?;
        validate_zip_entries(&mut archive)?;
        let bytes = read_zip_entry(&mut archive, MANIFEST_PATH, 16 * 1024)?;
        let probe: ManifestProbe =
            serde_json::from_slice(&bytes).map_err(|_| AppError::InvalidBackup)?;
        if probe.magic != MAGIC {
            return Err(AppError::InvalidBackup);
        }
        match probe.format_version {
            FORMAT_VERSION => {
                let manifest: Manifest =
                    serde_json::from_slice(&bytes).map_err(|_| AppError::InvalidBackup)?;
                validate_manifest(&manifest)?;
                Ok(BackupInspection {
                    protection: BackupProtection::Encrypted,
                    created_at_epoch_millis: manifest.created_at_epoch_millis,
                })
            }
            PLAINTEXT_FORMAT_VERSION => {
                let manifest: PlaintextManifest =
                    serde_json::from_slice(&bytes).map_err(|_| AppError::InvalidBackup)?;
                validate_plaintext_manifest(&manifest)?;
                Ok(BackupInspection {
                    protection: BackupProtection::Plaintext,
                    created_at_epoch_millis: manifest.created_at_epoch_millis,
                })
            }
            _ => Err(AppError::UnsupportedBackup),
        }
    }

    pub fn restore_auto(
        &self,
        password: Option<&str>,
        source: &Path,
    ) -> Result<RestoreSummary, AppError> {
        match self.inspect(source)?.protection {
            BackupProtection::Encrypted => {
                let password = password.ok_or(AppError::InvalidCredentials)?;
                validate_password(password)?;
                self.restore_archive(source, password)
            }
            BackupProtection::Plaintext => self.restore_plaintext_archive(source),
        }
    }

    fn write_archive(
        &self,
        destination: &Path,
        password: &str,
        created_at: i64,
        snapshot: PortableSnapshot,
    ) -> Result<(), AppError> {
        let mut archive_id = [0_u8; 16];
        let mut salt = [0_u8; 32];
        OsRng.fill_bytes(&mut archive_id);
        OsRng.fill_bytes(&mut salt);
        let key = derive_key(password, &salt);
        let seed = ManifestSeed {
            created_at_epoch_millis: created_at,
            archive_id: BASE64.encode(archive_id),
            salt: BASE64.encode(salt),
        };
        let binding = manifest_binding(&seed)?;
        let database = database_from_snapshot(&snapshot)?;
        let database_plaintext =
            Zeroizing::new(serde_json::to_vec(&database).map_err(|_| AppError::InvalidBackup)?);
        if database_plaintext.len() > MAX_DATABASE_BYTES {
            return Err(AppError::BackupTooLarge);
        }

        let mut entries = Vec::with_capacity(snapshot.attachments.len() + 1);
        entries.push(EncryptedEntry::new(
            DATABASE_PATH,
            encrypt_entry(&key, &binding, DATABASE_PATH, &database_plaintext)?,
        ));
        for (index, attachment) in snapshot.attachments.iter().enumerate() {
            let path = attachment_path(index + 1);
            let plaintext = self.crypto.decrypt_bytes(
                &attachment.record.encrypted_relative_path,
                &attachment.record.attachment.id,
            )?;
            if plaintext.len() as i64 != attachment.record.attachment.file_size
                || sha256_hex(&plaintext) != attachment.record.attachment.sha256
            {
                return Err(AppError::Cryptography);
            }
            entries.push(EncryptedEntry::new(
                &path,
                encrypt_entry(&key, &binding, &path, &plaintext)?,
            ));
        }
        let checksums = ChecksumIndex {
            format_version: FORMAT_VERSION,
            entries: entries
                .iter()
                .map(|entry| EntryChecksum {
                    path: entry.path.clone(),
                    ciphertext_size: entry.bytes.len() as u64,
                    ciphertext_sha256: sha256_hex(&entry.bytes),
                })
                .collect(),
        };
        let checksum_plaintext =
            Zeroizing::new(serde_json::to_vec(&checksums).map_err(|_| AppError::InvalidBackup)?);
        let encrypted_checksums =
            encrypt_entry(&key, &binding, CHECKSUMS_PATH, &checksum_plaintext)?;
        let manifest = Manifest {
            magic: MAGIC.to_owned(),
            format_version: FORMAT_VERSION,
            minimum_reader_version: MINIMUM_READER_VERSION,
            created_at_epoch_millis: created_at,
            archive_id: seed.archive_id,
            kdf: ManifestKdf {
                algorithm: KDF_ALGORITHM.to_owned(),
                iterations: KDF_ITERATIONS,
                salt: seed.salt,
                key_bits: 256,
            },
            cipher: CIPHER_ALGORITHM.to_owned(),
            checksums: ManifestChecksums {
                path: CHECKSUMS_PATH.to_owned(),
                ciphertext_size: encrypted_checksums.len() as u64,
                ciphertext_sha256: sha256_hex(&encrypted_checksums),
            },
        };
        let manifest_bytes = serde_json::to_vec(&manifest).map_err(|_| AppError::InvalidBackup)?;
        write_zip_atomically(
            destination,
            &manifest_bytes,
            CHECKSUMS_PATH,
            &encrypted_checksums,
            &entries,
        )
    }

    fn write_plaintext_archive(
        &self,
        destination: &Path,
        created_at: i64,
        snapshot: PortableSnapshot,
    ) -> Result<(), AppError> {
        let database = database_from_snapshot(&snapshot)?;
        let database_bytes =
            Zeroizing::new(serde_json::to_vec(&database).map_err(|_| AppError::InvalidBackup)?);
        if database_bytes.len() > MAX_DATABASE_BYTES {
            return Err(AppError::BackupTooLarge);
        }
        let mut entries = Vec::with_capacity(snapshot.attachments.len() + 1);
        entries.push(EncryptedEntry::new(
            PLAINTEXT_DATABASE_PATH,
            database_bytes.to_vec(),
        ));
        for (index, attachment) in snapshot.attachments.iter().enumerate() {
            let path = attachment_path(index + 1);
            let plaintext = self.crypto.decrypt_bytes(
                &attachment.record.encrypted_relative_path,
                &attachment.record.attachment.id,
            )?;
            if plaintext.len() as i64 != attachment.record.attachment.file_size
                || sha256_hex(&plaintext) != attachment.record.attachment.sha256
            {
                return Err(AppError::Cryptography);
            }
            entries.push(EncryptedEntry::new(&path, plaintext.to_vec()));
        }
        let checksums = PlaintextChecksumIndex {
            format_version: PLAINTEXT_FORMAT_VERSION,
            entries: entries
                .iter()
                .map(|entry| PlaintextEntryChecksum {
                    path: entry.path.clone(),
                    size: entry.bytes.len() as u64,
                    sha256: sha256_hex(&entry.bytes),
                })
                .collect(),
        };
        let checksum_bytes = serde_json::to_vec(&checksums).map_err(|_| AppError::InvalidBackup)?;
        let mut archive_id = [0_u8; 16];
        OsRng.fill_bytes(&mut archive_id);
        let manifest = PlaintextManifest {
            magic: MAGIC.to_owned(),
            format_version: PLAINTEXT_FORMAT_VERSION,
            minimum_reader_version: PLAINTEXT_MINIMUM_READER_VERSION,
            created_at_epoch_millis: created_at,
            archive_id: BASE64.encode(archive_id),
            protection: "NONE".to_owned(),
            checksums: PlaintextManifestChecksums {
                path: PLAINTEXT_CHECKSUMS_PATH.to_owned(),
                size: checksum_bytes.len() as u64,
                sha256: sha256_hex(&checksum_bytes),
            },
        };
        let manifest_bytes = serde_json::to_vec(&manifest).map_err(|_| AppError::InvalidBackup)?;
        write_zip_atomically(
            destination,
            &manifest_bytes,
            PLAINTEXT_CHECKSUMS_PATH,
            &checksum_bytes,
            &entries,
        )
    }

    fn restore_archive(&self, source: &Path, password: &str) -> Result<RestoreSummary, AppError> {
        let mut archive = ZipArchive::new(File::open(source)?).map_err(zip_invalid)?;
        let entry_names = validate_zip_entries(&mut archive)?;
        let manifest_bytes = read_zip_entry(&mut archive, MANIFEST_PATH, 16 * 1024)?;
        let manifest: Manifest =
            serde_json::from_slice(&manifest_bytes).map_err(|_| AppError::InvalidBackup)?;
        validate_manifest(&manifest)?;
        let seed = ManifestSeed {
            created_at_epoch_millis: manifest.created_at_epoch_millis,
            archive_id: manifest.archive_id.clone(),
            salt: manifest.kdf.salt.clone(),
        };
        let binding = manifest_binding(&seed)?;
        let salt = decode_fixed::<32>(&manifest.kdf.salt)?;
        let key = derive_key(password, &salt);
        let encrypted_checksums = read_zip_entry(
            &mut archive,
            CHECKSUMS_PATH,
            MAX_DATABASE_BYTES + ENTRY_HEADER_BYTES + 16,
        )?;
        if encrypted_checksums.len() as u64 != manifest.checksums.ciphertext_size
            || sha256_hex(&encrypted_checksums) != manifest.checksums.ciphertext_sha256
        {
            return Err(AppError::InvalidBackup);
        }
        let checksum_plaintext =
            decrypt_entry(&key, &binding, CHECKSUMS_PATH, &encrypted_checksums)?;
        let checksums: ChecksumIndex =
            serde_json::from_slice(&checksum_plaintext).map_err(|_| AppError::InvalidBackup)?;
        if checksums.format_version != FORMAT_VERSION {
            return Err(AppError::UnsupportedBackup);
        }
        validate_checksum_index(&checksums, &entry_names)?;
        let mut encrypted_entries = HashMap::new();
        for checksum in &checksums.entries {
            let bytes = read_zip_entry(
                &mut archive,
                &checksum.path,
                MAX_ENTRY_BYTES as usize + ENTRY_HEADER_BYTES + 16,
            )?;
            if bytes.len() as u64 != checksum.ciphertext_size
                || sha256_hex(&bytes) != checksum.ciphertext_sha256
            {
                return Err(AppError::InvalidBackup);
            }
            encrypted_entries.insert(checksum.path.clone(), bytes);
        }
        let database_encrypted = encrypted_entries
            .remove(DATABASE_PATH)
            .ok_or(AppError::InvalidBackup)?;
        let database_plaintext = decrypt_entry(&key, &binding, DATABASE_PATH, &database_encrypted)?;
        if database_plaintext.len() > MAX_DATABASE_BYTES {
            return Err(AppError::BackupTooLarge);
        }
        let database: DatabaseSnapshot =
            serde_json::from_slice(&database_plaintext).map_err(|_| AppError::InvalidBackup)?;
        validate_database(&database)?;
        let now = crate::services::now_epoch_millis()?;
        let portable = self.prepare_restore(
            database,
            encrypted_entries,
            RestoreEntryProtection::Encrypted {
                key: &key,
                binding: &binding,
            },
        )?;
        let summary = RestoreSummary {
            restored_item_count: portable.items.len(),
            restored_attachment_count: portable.attachments.len(),
        };
        let encrypted_files: Vec<(String, String)> = portable
            .attachments
            .iter()
            .map(|item| {
                (
                    item.record.encrypted_relative_path.clone(),
                    item.record.attachment.id.clone(),
                )
            })
            .collect();
        if let Err(error) = self.repository.restore_portable_snapshot(&portable, now) {
            for (path, id) in encrypted_files {
                let _ = self.crypto.remove(&path, &id);
            }
            return Err(error);
        }
        Ok(summary)
    }

    fn restore_plaintext_archive(&self, source: &Path) -> Result<RestoreSummary, AppError> {
        let mut archive = ZipArchive::new(File::open(source)?).map_err(zip_invalid)?;
        let entry_names = validate_zip_entries(&mut archive)?;
        let manifest_bytes = read_zip_entry(&mut archive, MANIFEST_PATH, 16 * 1024)?;
        let manifest: PlaintextManifest =
            serde_json::from_slice(&manifest_bytes).map_err(|_| AppError::InvalidBackup)?;
        validate_plaintext_manifest(&manifest)?;
        let checksum_bytes =
            read_zip_entry(&mut archive, PLAINTEXT_CHECKSUMS_PATH, MAX_DATABASE_BYTES)?;
        if checksum_bytes.len() as u64 != manifest.checksums.size
            || sha256_hex(&checksum_bytes) != manifest.checksums.sha256
        {
            return Err(AppError::InvalidBackup);
        }
        let checksums: PlaintextChecksumIndex =
            serde_json::from_slice(&checksum_bytes).map_err(|_| AppError::InvalidBackup)?;
        validate_plaintext_checksum_index(&checksums, &entry_names)?;
        let mut entries = HashMap::new();
        for checksum in &checksums.entries {
            let bytes = read_zip_entry(&mut archive, &checksum.path, MAX_ENTRY_BYTES as usize)?;
            if bytes.len() as u64 != checksum.size || sha256_hex(&bytes) != checksum.sha256 {
                return Err(AppError::InvalidBackup);
            }
            entries.insert(checksum.path.clone(), bytes);
        }
        let database_bytes = entries
            .remove(PLAINTEXT_DATABASE_PATH)
            .ok_or(AppError::InvalidBackup)?;
        if database_bytes.len() > MAX_DATABASE_BYTES {
            return Err(AppError::BackupTooLarge);
        }
        let database: DatabaseSnapshot =
            serde_json::from_slice(&database_bytes).map_err(|_| AppError::InvalidBackup)?;
        validate_database(&database)?;
        let portable =
            self.prepare_restore(database, entries, RestoreEntryProtection::Plaintext)?;
        let summary = RestoreSummary {
            restored_item_count: portable.items.len(),
            restored_attachment_count: portable.attachments.len(),
        };
        let encrypted_files: Vec<(String, String)> = portable
            .attachments
            .iter()
            .map(|item| {
                (
                    item.record.encrypted_relative_path.clone(),
                    item.record.attachment.id.clone(),
                )
            })
            .collect();
        if let Err(error) = self
            .repository
            .restore_portable_snapshot(&portable, crate::services::now_epoch_millis()?)
        {
            for (path, id) in encrypted_files {
                let _ = self.crypto.remove(&path, &id);
            }
            return Err(error);
        }
        Ok(summary)
    }

    fn prepare_restore(
        &self,
        database: DatabaseSnapshot,
        mut encrypted_entries: HashMap<String, Vec<u8>>,
        protection: RestoreEntryProtection<'_>,
    ) -> Result<PortableSnapshot, AppError> {
        let item_ids: HashMap<String, String> = database
            .items
            .iter()
            .map(|item| (item.id.clone(), new_id()))
            .collect();
        let tag_ids: HashMap<String, String> = database
            .tags
            .iter()
            .map(|tag| (tag.id.clone(), new_id()))
            .collect();
        let items = database
            .items
            .into_iter()
            .map(|item| {
                let conflict_origin_id = item
                    .conflict_origin_id
                    .as_ref()
                    .map(|id| item_ids.get(id).cloned().unwrap_or_else(|| id.clone()));
                PortableItem {
                    id: item_ids[&item.id].clone(),
                    item_type: item.item_type,
                    color: item.color,
                    title: item.title,
                    body: item.body,
                    ocr_text: item.ocr_text,
                    is_pinned: item.pinned,
                    is_favorite: item.favorite,
                    is_archived: item.archived,
                    sort_position: item.sort_position,
                    created_at: item.created_at,
                    updated_at: item.updated_at,
                    local_revision: item.local_revision,
                    deleted_at: item.deleted_at,
                    conflict_origin_id,
                    body_document_json: item.body_document,
                }
            })
            .collect();
        let tags = database
            .tags
            .into_iter()
            .map(|tag| PortableTag {
                id: tag_ids[&tag.id].clone(),
                name: tag.name,
                normalized_name: tag.normalized_name,
                created_at: tag.created_at,
            })
            .collect();
        let item_tags = database
            .item_tags
            .into_iter()
            .map(|reference| PortableItemTag {
                item_id: item_ids[&reference.item_id].clone(),
                tag_id: tag_ids[&reference.tag_id].clone(),
            })
            .collect();
        let mut attachments = Vec::with_capacity(database.attachments.len());
        for attachment in database.attachments {
            let expected_file_size = attachment.file_size;
            let prepared = (|| {
                let bytes = encrypted_entries
                    .remove(&attachment.content_entry)
                    .ok_or(AppError::InvalidBackup)?;
                let plaintext = match protection {
                    RestoreEntryProtection::Encrypted { key, binding } => {
                        decrypt_entry(key, binding, &attachment.content_entry, &bytes)?
                    }
                    RestoreEntryProtection::Plaintext => Zeroizing::new(bytes),
                };
                if plaintext.len() as i64 != expected_file_size
                    || sha256_hex(&plaintext) != attachment.sha256
                {
                    return Err(AppError::InvalidBackup);
                }
                let id = new_id();
                let encrypted = self.crypto.encrypt_restored(
                    &plaintext,
                    &attachment.filename,
                    &attachment.mime_type,
                    &id,
                )?;
                Ok(PortableAttachment {
                    record: AttachmentRecord {
                        attachment: VaultAttachment {
                            id,
                            parent_item_id: item_ids[&attachment.parent_item_id].clone(),
                            display_name: encrypted.display_name,
                            mime_type: encrypted.mime_type,
                            file_size: encrypted.plaintext_size,
                            sha256: encrypted.sha256,
                            created_at_epoch_millis: attachment.created_at,
                        },
                        encrypted_relative_path: encrypted.relative_path,
                    },
                    image_width: attachment.image_width,
                    image_height: attachment.image_height,
                    pdf_page_count: attachment.pdf_page_count,
                    ocr_state: attachment.ocr_state,
                    ocr_text: attachment.ocr_text,
                    ocr_source_checksum: attachment.ocr_source_checksum,
                    ocr_failure_code: attachment.ocr_failure_code,
                    ocr_updated_at: attachment.ocr_updated_at,
                })
            })();
            let prepared = match prepared {
                Ok(prepared) => prepared,
                Err(error) => {
                    cleanup_attachments(&self.crypto, &attachments);
                    return Err(error);
                }
            };
            if prepared.record.attachment.file_size != expected_file_size {
                let _ = self.crypto.remove(
                    &prepared.record.encrypted_relative_path,
                    &prepared.record.attachment.id,
                );
                cleanup_attachments(&self.crypto, &attachments);
                return Err(AppError::InvalidBackup);
            }
            attachments.push(prepared);
        }
        let mut alerts_by_entry: HashMap<String, Vec<DatabaseDatedEntryAlert>> = HashMap::new();
        for alert in database.dated_entry_alerts {
            alerts_by_entry
                .entry(alert.entry_id.clone())
                .or_default()
                .push(alert);
        }
        let dated_entries = database
            .dated_entries
            .into_iter()
            .map(|entry| {
                let item_id = item_ids
                    .get(&entry.item_id)
                    .cloned()
                    .ok_or(AppError::InvalidBackup)?;
                Ok(PortableDatedEntry {
                    entry: DatedEntry {
                        id: new_id(),
                        item_id,
                        entry_type: entry.entry_type,
                        label: entry.label,
                        occurrence_at_epoch_millis: entry.occurrence_at,
                        is_all_day: entry.all_day,
                        time_zone_id: entry.time_zone_id,
                        recurrence_unit: entry.recurrence_unit,
                        recurrence_interval: entry.recurrence_interval,
                        completed_at_epoch_millis: entry.completed_at,
                        created_at_epoch_millis: entry.created_at,
                        updated_at_epoch_millis: entry.updated_at,
                        alerts: entry
                            .alerts
                            .unwrap_or_default()
                            .into_iter()
                            .map(|alert| (alert.id, alert.lead_time_minutes))
                            .chain(
                                alerts_by_entry
                                    .remove(&entry.id)
                                    .unwrap_or_default()
                                    .into_iter()
                                    .map(|alert| (alert.id, alert.lead_time_minutes)),
                            )
                            .map(|(_id, lead_time_minutes)| DatedEntryAlert {
                                id: new_id(),
                                lead_time_minutes,
                                snoozed_until_epoch_millis: None,
                            })
                            .collect(),
                    },
                })
            })
            .collect::<Result<Vec<_>, AppError>>()?;
        if !alerts_by_entry.is_empty() {
            cleanup_attachments(&self.crypto, &attachments);
            return Err(AppError::InvalidBackup);
        }
        if !encrypted_entries.is_empty() {
            cleanup_attachments(&self.crypto, &attachments);
            return Err(AppError::InvalidBackup);
        }
        Ok(PortableSnapshot {
            items,
            tags,
            item_tags,
            attachments,
            dated_entries,
        })
    }
}

fn cleanup_attachments(crypto: &AttachmentCrypto, attachments: &[PortableAttachment]) {
    for attachment in attachments {
        let _ = crypto.remove(
            &attachment.record.encrypted_relative_path,
            &attachment.record.attachment.id,
        );
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    magic: String,
    format_version: u32,
    minimum_reader_version: u32,
    created_at_epoch_millis: i64,
    archive_id: String,
    kdf: ManifestKdf,
    cipher: String,
    checksums: ManifestChecksums,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ManifestProbe {
    magic: String,
    format_version: u32,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PlaintextManifest {
    magic: String,
    format_version: u32,
    minimum_reader_version: u32,
    created_at_epoch_millis: i64,
    archive_id: String,
    protection: String,
    checksums: PlaintextManifestChecksums,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PlaintextManifestChecksums {
    path: String,
    size: u64,
    sha256: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ManifestKdf {
    algorithm: String,
    iterations: u32,
    salt: String,
    key_bits: u32,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ManifestChecksums {
    path: String,
    ciphertext_size: u64,
    ciphertext_sha256: String,
}

struct ManifestSeed {
    created_at_epoch_millis: i64,
    archive_id: String,
    salt: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ManifestBinding<'a> {
    magic: &'static str,
    format_version: u32,
    minimum_reader_version: u32,
    created_at_epoch_millis: i64,
    archive_id: &'a str,
    kdf_algorithm: &'static str,
    kdf_iterations: u32,
    salt: &'a str,
    key_bits: u32,
    cipher: &'static str,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ChecksumIndex {
    format_version: u32,
    entries: Vec<EntryChecksum>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PlaintextChecksumIndex {
    format_version: u32,
    entries: Vec<PlaintextEntryChecksum>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PlaintextEntryChecksum {
    path: String,
    size: u64,
    sha256: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct EntryChecksum {
    path: String,
    ciphertext_size: u64,
    ciphertext_sha256: String,
}

struct EncryptedEntry {
    path: String,
    bytes: Vec<u8>,
}

impl EncryptedEntry {
    fn new(path: &str, bytes: Vec<u8>) -> Self {
        Self {
            path: path.to_owned(),
            bytes,
        }
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DatabaseSnapshot {
    schema_version: u32,
    item_count: usize,
    attachment_count: usize,
    items: Vec<DatabaseItem>,
    tags: Vec<DatabaseTag>,
    item_tags: Vec<DatabaseItemTag>,
    #[serde(default)]
    dated_entries: Vec<DatabaseDatedEntry>,
    #[serde(default)]
    dated_entry_alerts: Vec<DatabaseDatedEntryAlert>,
    attachments: Vec<DatabaseAttachment>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DatabaseItem {
    id: String,
    #[serde(rename = "type")]
    item_type: String,
    color: String,
    title: String,
    body: String,
    #[serde(default)]
    body_document: Option<String>,
    ocr_text: String,
    pinned: bool,
    favorite: bool,
    archived: bool,
    #[serde(default)]
    sort_position: i64,
    created_at: i64,
    updated_at: i64,
    local_revision: i64,
    deleted_at: Option<i64>,
    conflict_origin_id: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DatabaseTag {
    id: String,
    name: String,
    normalized_name: String,
    created_at: i64,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DatabaseItemTag {
    item_id: String,
    tag_id: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DatabaseAttachment {
    id: String,
    parent_item_id: String,
    filename: String,
    mime_type: String,
    file_size: i64,
    image_width: Option<i64>,
    image_height: Option<i64>,
    pdf_page_count: Option<i64>,
    sha256: String,
    created_at: i64,
    ocr_state: String,
    ocr_text: String,
    ocr_source_checksum: Option<String>,
    ocr_failure_code: Option<String>,
    ocr_updated_at: Option<i64>,
    content_entry: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DatabaseDatedEntry {
    id: String,
    item_id: String,
    #[serde(rename = "type")]
    entry_type: String,
    label: String,
    occurrence_at: i64,
    all_day: bool,
    time_zone_id: String,
    recurrence_unit: Option<String>,
    recurrence_interval: Option<i64>,
    completed_at: Option<i64>,
    created_at: i64,
    updated_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    alerts: Option<Vec<LegacyDatedEntryAlert>>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DatabaseDatedEntryAlert {
    id: String,
    entry_id: String,
    lead_time_minutes: i64,
    created_at: i64,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LegacyDatedEntryAlert {
    id: String,
    lead_time_minutes: i64,
}

fn database_from_snapshot(snapshot: &PortableSnapshot) -> Result<DatabaseSnapshot, AppError> {
    let items = snapshot
        .items
        .iter()
        .map(|item| DatabaseItem {
            id: item.id.clone(),
            item_type: item.item_type.clone(),
            color: item.color.clone(),
            title: item.title.clone(),
            body: item.body.clone(),
            ocr_text: item.ocr_text.clone(),
            pinned: item.is_pinned,
            favorite: item.is_favorite,
            archived: item.is_archived,
            sort_position: item.sort_position,
            created_at: item.created_at,
            updated_at: item.updated_at,
            local_revision: item.local_revision,
            deleted_at: item.deleted_at,
            conflict_origin_id: item.conflict_origin_id.clone(),
            body_document: item.body_document_json.clone(),
        })
        .collect();
    let tags = snapshot
        .tags
        .iter()
        .map(|tag| DatabaseTag {
            id: tag.id.clone(),
            name: tag.name.clone(),
            normalized_name: tag.normalized_name.clone(),
            created_at: tag.created_at,
        })
        .collect();
    let item_tags = snapshot
        .item_tags
        .iter()
        .map(|reference| DatabaseItemTag {
            item_id: reference.item_id.clone(),
            tag_id: reference.tag_id.clone(),
        })
        .collect();
    let attachments = snapshot
        .attachments
        .iter()
        .enumerate()
        .map(|(index, attachment)| DatabaseAttachment {
            id: attachment.record.attachment.id.clone(),
            parent_item_id: attachment.record.attachment.parent_item_id.clone(),
            filename: attachment.record.attachment.display_name.clone(),
            mime_type: attachment.record.attachment.mime_type.clone(),
            file_size: attachment.record.attachment.file_size,
            image_width: attachment.image_width,
            image_height: attachment.image_height,
            pdf_page_count: attachment.pdf_page_count,
            sha256: attachment.record.attachment.sha256.clone(),
            created_at: attachment.record.attachment.created_at_epoch_millis,
            ocr_state: attachment.ocr_state.clone(),
            ocr_text: attachment.ocr_text.clone(),
            ocr_source_checksum: attachment.ocr_source_checksum.clone(),
            ocr_failure_code: attachment.ocr_failure_code.clone(),
            ocr_updated_at: attachment.ocr_updated_at,
            content_entry: attachment_path(index + 1),
        })
        .collect();
    let dated_entries = snapshot
        .dated_entries
        .iter()
        .map(|portable| {
            let entry = &portable.entry;
            DatabaseDatedEntry {
                id: entry.id.clone(),
                item_id: entry.item_id.clone(),
                entry_type: entry.entry_type.clone(),
                label: entry.label.clone(),
                occurrence_at: entry.occurrence_at_epoch_millis,
                all_day: entry.is_all_day,
                time_zone_id: entry.time_zone_id.clone(),
                recurrence_unit: entry.recurrence_unit.clone(),
                recurrence_interval: entry.recurrence_interval,
                completed_at: entry.completed_at_epoch_millis,
                created_at: entry.created_at_epoch_millis,
                updated_at: entry.updated_at_epoch_millis,
                alerts: None,
            }
        })
        .collect();
    let dated_entry_alerts = snapshot
        .dated_entries
        .iter()
        .flat_map(|portable| {
            portable
                .entry
                .alerts
                .iter()
                .map(|alert| DatabaseDatedEntryAlert {
                    id: alert.id.clone(),
                    entry_id: portable.entry.id.clone(),
                    lead_time_minutes: alert.lead_time_minutes,
                    created_at: portable.entry.created_at_epoch_millis,
                })
        })
        .collect();
    Ok(DatabaseSnapshot {
        schema_version: DATABASE_SCHEMA_VERSION,
        item_count: snapshot.items.len(),
        attachment_count: snapshot.attachments.len(),
        items,
        tags,
        item_tags,
        dated_entries,
        dated_entry_alerts,
        attachments,
    })
}

fn validate_manifest(manifest: &Manifest) -> Result<(), AppError> {
    if manifest.magic != MAGIC
        || manifest.format_version != FORMAT_VERSION
        || manifest.minimum_reader_version != MINIMUM_READER_VERSION
        || manifest.created_at_epoch_millis < 0
        || manifest.kdf.algorithm != KDF_ALGORITHM
        || manifest.kdf.iterations != KDF_ITERATIONS
        || manifest.kdf.key_bits != 256
        || manifest.cipher != CIPHER_ALGORITHM
        || manifest.checksums.path != CHECKSUMS_PATH
        || manifest.checksums.ciphertext_size == 0
        || !is_sha256(&manifest.checksums.ciphertext_sha256)
    {
        return Err(AppError::UnsupportedBackup);
    }
    decode_fixed::<16>(&manifest.archive_id)?;
    decode_fixed::<32>(&manifest.kdf.salt)?;
    Ok(())
}

fn validate_plaintext_manifest(manifest: &PlaintextManifest) -> Result<(), AppError> {
    if manifest.magic != MAGIC
        || manifest.format_version != PLAINTEXT_FORMAT_VERSION
        || manifest.minimum_reader_version != PLAINTEXT_MINIMUM_READER_VERSION
        || manifest.created_at_epoch_millis < 0
        || manifest.protection != "NONE"
        || manifest.checksums.path != PLAINTEXT_CHECKSUMS_PATH
        || manifest.checksums.size == 0
        || !is_sha256(&manifest.checksums.sha256)
    {
        return Err(AppError::UnsupportedBackup);
    }
    decode_fixed::<16>(&manifest.archive_id)?;
    Ok(())
}

fn validate_database(database: &DatabaseSnapshot) -> Result<(), AppError> {
    if !(MINIMUM_DATABASE_SCHEMA_VERSION..=DATABASE_SCHEMA_VERSION)
        .contains(&database.schema_version)
        || database.item_count != database.items.len()
        || database.attachment_count != database.attachments.len()
        || database.items.len() > MAX_ITEMS
        || database.attachments.len() > MAX_ATTACHMENTS
    {
        return Err(AppError::InvalidBackup);
    }
    let mut item_ids = HashSet::new();
    for item in &database.items {
        if !is_safe_id(&item.id)
            || !item_ids.insert(item.id.as_str())
            || item.item_type != "NOTE"
            || !matches!(
                item.color.as_str(),
                "DEFAULT" | "RED" | "ORANGE" | "YELLOW" | "GREEN" | "BLUE" | "PURPLE"
            )
            || item.title.chars().count() > 500
            || item.body.chars().count() > 100_000
            || item.ocr_text.chars().count() > 200_000
            || item.created_at < 0
            || item.updated_at < 0
            || item.local_revision < 1
            || item.deleted_at.is_some_and(|value| value < 0)
            || item
                .conflict_origin_id
                .as_deref()
                .is_some_and(|id| !is_safe_id(id))
        {
            return Err(AppError::InvalidBackup);
        }
        if let Some(document_json) = &item.body_document {
            let document: NoteBodyDocument =
                serde_json::from_str(document_json).map_err(|_| AppError::InvalidBackup)?;
            if !matches!(
                validate_note_document(&item.title, &document),
                Ok(derived) if derived == item.body
            ) {
                return Err(AppError::InvalidBackup);
            }
        }
    }
    let mut tag_ids = HashSet::new();
    let mut normalized_tags = HashSet::new();
    for tag in &database.tags {
        let canonical_name: String = tag
            .name
            .nfkc()
            .collect::<String>()
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");
        let canonical_normalized = canonical_name.to_lowercase();
        if !is_safe_id(&tag.id)
            || !tag_ids.insert(tag.id.as_str())
            || !normalized_tags.insert(tag.normalized_name.as_str())
            || !(1..=64).contains(&tag.name.chars().count())
            || !(1..=64).contains(&tag.normalized_name.chars().count())
            || canonical_normalized != tag.normalized_name
            || tag.created_at < 0
        {
            return Err(AppError::InvalidBackup);
        }
    }
    let mut relations = HashSet::new();
    for reference in &database.item_tags {
        if !item_ids.contains(reference.item_id.as_str())
            || !tag_ids.contains(reference.tag_id.as_str())
            || !relations.insert((reference.item_id.as_str(), reference.tag_id.as_str()))
        {
            return Err(AppError::InvalidBackup);
        }
    }
    let mut attachment_ids = HashSet::new();
    let mut content_paths = HashSet::new();
    for attachment in &database.attachments {
        if !is_safe_id(&attachment.id)
            || !attachment_ids.insert(attachment.id.as_str())
            || !item_ids.contains(attachment.parent_item_id.as_str())
            || !(0..=100 * 1024 * 1024).contains(&attachment.file_size)
            || !is_sha256(&attachment.sha256)
            || attachment.created_at < 0
            || !is_attachment_path(&attachment.content_entry)
            || !content_paths.insert(attachment.content_entry.as_str())
            || attachment.filename.is_empty()
            || attachment.filename.chars().count() > 180
            || attachment.filename.contains(['/', '\\', '\0'])
            || attachment.image_width.is_some_and(|value| value <= 0)
            || attachment.image_height.is_some_and(|value| value <= 0)
            || attachment.pdf_page_count.is_some_and(|value| value <= 0)
            || !matches!(
                attachment.ocr_state.as_str(),
                "NOT_APPLICABLE" | "PENDING" | "PROCESSING" | "COMPLETE" | "FAILED"
            )
            || attachment.ocr_text.chars().count() > 200_000
            || attachment
                .ocr_source_checksum
                .as_deref()
                .is_some_and(|value| !is_sha256(value))
            || attachment
                .ocr_failure_code
                .as_deref()
                .is_some_and(|value| value.chars().count() > 128)
            || attachment.ocr_updated_at.is_some_and(|value| value < 0)
        {
            return Err(AppError::InvalidBackup);
        }
    }
    let mut dated_entry_ids = HashSet::new();
    let mut alert_ids = HashSet::new();
    let mut lead_times_by_entry: HashMap<&str, HashSet<i64>> = HashMap::new();
    for entry in &database.dated_entries {
        let recurrence_valid = match (&entry.recurrence_unit, entry.recurrence_interval) {
            (None, None) => true,
            (Some(unit), Some(interval)) => {
                matches!(unit.as_str(), "DAY" | "WEEK" | "MONTH" | "YEAR")
                    && (1..=999).contains(&interval)
            }
            _ => false,
        };
        if !is_safe_id(&entry.id)
            || !dated_entry_ids.insert(entry.id.as_str())
            || !item_ids.contains(entry.item_id.as_str())
            || !matches!(
                entry.entry_type.as_str(),
                "REMINDER" | "DEADLINE" | "IMPORTANT_DATE" | "RENEWAL"
            )
            || entry.label.chars().count() > 500
            || entry.label.contains('\0')
            || entry.occurrence_at < 0
            || entry.time_zone_id.parse::<chrono_tz::Tz>().is_err()
            || !recurrence_valid
            || entry.completed_at.is_some_and(|value| value < 0)
            || entry.created_at < 0
            || entry.updated_at < 0
        {
            return Err(AppError::InvalidBackup);
        }
        for alert in entry.alerts.as_deref().unwrap_or_default() {
            let lead_times = lead_times_by_entry.entry(&entry.id).or_default();
            if !is_safe_id(&alert.id)
                || !alert_ids.insert(alert.id.as_str())
                || !lead_times.insert(alert.lead_time_minutes)
                || !(0..=5_256_000).contains(&alert.lead_time_minutes)
                || lead_times.len() > 5
            {
                return Err(AppError::InvalidBackup);
            }
        }
    }
    for alert in &database.dated_entry_alerts {
        let lead_times = lead_times_by_entry
            .entry(alert.entry_id.as_str())
            .or_default();
        if !is_safe_id(&alert.id)
            || !alert_ids.insert(alert.id.as_str())
            || !dated_entry_ids.contains(alert.entry_id.as_str())
            || !lead_times.insert(alert.lead_time_minutes)
            || !(0..=5_256_000).contains(&alert.lead_time_minutes)
            || alert.created_at < 0
            || lead_times.len() > 5
        {
            return Err(AppError::InvalidBackup);
        }
    }
    Ok(())
}

fn validate_checksum_index(
    checksums: &ChecksumIndex,
    zip_names: &HashSet<String>,
) -> Result<(), AppError> {
    let mut expected = HashSet::from([MANIFEST_PATH.to_owned(), CHECKSUMS_PATH.to_owned()]);
    let mut paths = HashSet::new();
    let mut saw_database = false;
    for entry in &checksums.entries {
        if !(entry.path == DATABASE_PATH || is_attachment_path(&entry.path))
            || !paths.insert(entry.path.clone())
            || entry.ciphertext_size == 0
            || entry.ciphertext_size > MAX_ENTRY_BYTES + ENTRY_HEADER_BYTES as u64 + 16
            || !is_sha256(&entry.ciphertext_sha256)
        {
            return Err(AppError::InvalidBackup);
        }
        saw_database |= entry.path == DATABASE_PATH;
        expected.insert(entry.path.clone());
    }
    if !saw_database || &expected != zip_names {
        return Err(AppError::InvalidBackup);
    }
    Ok(())
}

fn validate_plaintext_checksum_index(
    checksums: &PlaintextChecksumIndex,
    zip_names: &HashSet<String>,
) -> Result<(), AppError> {
    if checksums.format_version != PLAINTEXT_FORMAT_VERSION {
        return Err(AppError::UnsupportedBackup);
    }
    let mut expected = HashSet::from([
        MANIFEST_PATH.to_owned(),
        PLAINTEXT_CHECKSUMS_PATH.to_owned(),
    ]);
    let mut paths = HashSet::new();
    let mut saw_database = false;
    for entry in &checksums.entries {
        if !(entry.path == PLAINTEXT_DATABASE_PATH || is_attachment_path(&entry.path))
            || !paths.insert(entry.path.clone())
            || entry.size > MAX_ENTRY_BYTES
            || !is_sha256(&entry.sha256)
        {
            return Err(AppError::InvalidBackup);
        }
        saw_database |= entry.path == PLAINTEXT_DATABASE_PATH;
        expected.insert(entry.path.clone());
    }
    if !saw_database || &expected != zip_names {
        return Err(AppError::InvalidBackup);
    }
    Ok(())
}

fn validate_zip_entries(archive: &mut ZipArchive<File>) -> Result<HashSet<String>, AppError> {
    if archive.len() > MAX_ATTACHMENTS + 3 {
        return Err(AppError::BackupTooLarge);
    }
    let mut names = HashSet::new();
    let mut total_size = 0_u64;
    for index in 0..archive.len() {
        let entry = archive.by_index(index).map_err(zip_invalid)?;
        let name = entry.name();
        if entry.is_dir()
            || name.contains(['\\', '\0'])
            || name.starts_with('/')
            || name
                .split('/')
                .any(|part| part.is_empty() || part == "." || part == "..")
            || !(name == MANIFEST_PATH
                || name == CHECKSUMS_PATH
                || name == DATABASE_PATH
                || name == PLAINTEXT_CHECKSUMS_PATH
                || name == PLAINTEXT_DATABASE_PATH
                || is_attachment_path(name))
            || !names.insert(name.to_owned())
        {
            return Err(AppError::InvalidBackup);
        }
        if entry.compressed_size() > 0 && entry.size() / entry.compressed_size().max(1) > 100 {
            return Err(AppError::BackupTooLarge);
        }
        total_size = total_size
            .checked_add(entry.size())
            .ok_or(AppError::BackupTooLarge)?;
        if total_size > MAX_ARCHIVE_BYTES {
            return Err(AppError::BackupTooLarge);
        }
    }
    Ok(names)
}

fn read_zip_entry(
    archive: &mut ZipArchive<File>,
    name: &str,
    maximum: usize,
) -> Result<Vec<u8>, AppError> {
    let mut entry = archive.by_name(name).map_err(zip_invalid)?;
    if entry.size() > maximum as u64 {
        return Err(AppError::BackupTooLarge);
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry
        .by_ref()
        .take(maximum as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| AppError::InvalidBackup)?;
    if bytes.len() > maximum {
        return Err(AppError::BackupTooLarge);
    }
    Ok(bytes)
}

fn write_zip_atomically(
    destination: &Path,
    manifest: &[u8],
    checksum_path: &str,
    checksums: &[u8],
    entries: &[EncryptedEntry],
) -> Result<(), AppError> {
    let parent = destination.parent().ok_or_else(|| {
        AppError::Storage(std::io::Error::other(
            "backup destination directory unavailable",
        ))
    })?;
    let pending = parent.join(format!(".vaultnote-backup-{}.tmp", new_id()));
    let result = (|| {
        let file = File::options()
            .write(true)
            .create_new(true)
            .open(&pending)?;
        let mut zip = ZipWriter::new(file);
        let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
        zip.start_file(MANIFEST_PATH, options)
            .map_err(zip_invalid)?;
        zip.write_all(manifest)?;
        zip.start_file(checksum_path, options)
            .map_err(zip_invalid)?;
        zip.write_all(checksums)?;
        for entry in entries {
            zip.start_file(&entry.path, options).map_err(zip_invalid)?;
            zip.write_all(&entry.bytes)?;
        }
        let file = zip.finish().map_err(zip_invalid)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&pending, destination)?;
        sync_directory(parent)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&pending);
    }
    result
}

fn encrypt_entry(
    key: &Zeroizing<[u8; 32]>,
    binding: &[u8],
    path: &str,
    plaintext: &[u8],
) -> Result<Vec<u8>, AppError> {
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).map_err(|_| AppError::Cryptography)?;
    let mut nonce = [0_u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let mut header = [0_u8; ENTRY_HEADER_BYTES];
    header[..4].copy_from_slice(b"VNBE");
    header[4] = 1;
    header[5] = 12;
    header[6..].copy_from_slice(&nonce);
    let aad = entry_aad(&header, binding, path);
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: &aad,
            },
        )
        .map_err(|_| AppError::Cryptography)?;
    let mut envelope = Vec::with_capacity(header.len() + ciphertext.len());
    envelope.extend_from_slice(&header);
    envelope.extend_from_slice(&ciphertext);
    Ok(envelope)
}

fn decrypt_entry(
    key: &Zeroizing<[u8; 32]>,
    binding: &[u8],
    path: &str,
    envelope: &[u8],
) -> Result<Zeroizing<Vec<u8>>, AppError> {
    if envelope.len() < ENTRY_HEADER_BYTES + 16
        || &envelope[..4] != b"VNBE"
        || envelope[4] != 1
        || envelope[5] != 12
    {
        return Err(AppError::InvalidBackup);
    }
    let header: &[u8; ENTRY_HEADER_BYTES] = envelope[..ENTRY_HEADER_BYTES]
        .try_into()
        .map_err(|_| AppError::InvalidBackup)?;
    let nonce = Nonce::from_slice(&header[6..18]);
    let aad = entry_aad(header, binding, path);
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).map_err(|_| AppError::Cryptography)?;
    let plaintext = cipher
        .decrypt(
            nonce,
            Payload {
                msg: &envelope[ENTRY_HEADER_BYTES..],
                aad: &aad,
            },
        )
        .map_err(|_| AppError::InvalidBackup)?;
    Ok(Zeroizing::new(plaintext))
}

fn entry_aad(header: &[u8; ENTRY_HEADER_BYTES], binding: &[u8], path: &str) -> Vec<u8> {
    let mut aad = Vec::with_capacity(header.len() + binding.len() + path.len());
    aad.extend_from_slice(header);
    aad.extend_from_slice(binding);
    aad.extend_from_slice(path.as_bytes());
    aad
}

fn manifest_binding(seed: &ManifestSeed) -> Result<Vec<u8>, AppError> {
    serde_json::to_vec(&ManifestBinding {
        magic: MAGIC,
        format_version: FORMAT_VERSION,
        minimum_reader_version: MINIMUM_READER_VERSION,
        created_at_epoch_millis: seed.created_at_epoch_millis,
        archive_id: &seed.archive_id,
        kdf_algorithm: KDF_ALGORITHM,
        kdf_iterations: KDF_ITERATIONS,
        salt: &seed.salt,
        key_bits: 256,
        cipher: CIPHER_ALGORITHM,
    })
    .map_err(|_| AppError::InvalidBackup)
}

fn derive_key(password: &str, salt: &[u8; 32]) -> Zeroizing<[u8; 32]> {
    let mut key = Zeroizing::new([0_u8; 32]);
    pbkdf2_hmac::<Sha256>(password.as_bytes(), salt, KDF_ITERATIONS, key.as_mut());
    key
}

fn validate_password(password: &str) -> Result<(), AppError> {
    let count = password.chars().count();
    if !(12..=128).contains(&count) || password.contains('\0') {
        return Err(AppError::InvalidInput {
            field: "password",
            reason: "must contain 12 to 128 Unicode characters and no NUL".to_owned(),
        });
    }
    Ok(())
}

fn decode_fixed<const N: usize>(value: &str) -> Result<[u8; N], AppError> {
    BASE64
        .decode(value)
        .map_err(|_| AppError::InvalidBackup)?
        .try_into()
        .map_err(|_| AppError::InvalidBackup)
}

fn attachment_path(index: usize) -> String {
    format!("attachments/{index:08}.bin")
}

fn is_attachment_path(path: &str) -> bool {
    path.len() == "attachments/00000000.bin".len()
        && path.starts_with("attachments/")
        && path.ends_with(".bin")
        && path[12..20].bytes().all(|byte| byte.is_ascii_digit())
}

fn is_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn is_safe_id(value: &str) -> bool {
    (1..=128).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut result = String::with_capacity(64);
    for byte in digest {
        use std::fmt::Write as _;
        write!(result, "{byte:02x}").expect("writing to a string cannot fail");
    }
    result
}

fn new_id() -> String {
    Uuid::new_v4().hyphenated().to_string()
}

fn zip_invalid(_: zip::result::ZipError) -> AppError {
    AppError::InvalidBackup
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
    use crate::{
        database::Database,
        models::{AttachmentRecord, VaultAttachment, VaultSection},
        repository::{SqliteVaultRepository, VaultRepository},
        vault_key::MasterKey,
    };
    use tempfile::{TempDir, tempdir};

    const TEST_PASSWORD: &str = "correct horse battery staple";

    fn populated_service() -> (
        TempDir,
        Arc<SqliteVaultRepository>,
        AttachmentCrypto,
        BackupService,
        String,
    ) {
        let directory = tempdir().expect("temporary directory should exist");
        let repository = Arc::new(SqliteVaultRepository::new(
            Database::open_in_memory().expect("database should open"),
        ));
        let item_id = repository
            .create_note("Cross-platform", "Android and desktop", 1_000)
            .expect("note should be created");
        let crypto = AttachmentCrypto::new(Arc::new(MasterKey::for_tests()), directory.path())
            .expect("attachment crypto should initialize");
        let attachment_id = Uuid::new_v4().hyphenated().to_string();
        let plaintext = b"portable attachment payload";
        let encrypted = crypto
            .encrypt_restored(
                plaintext,
                "travel-plan.pdf",
                "application/pdf",
                &attachment_id,
            )
            .expect("attachment should be encrypted locally");
        repository
            .add_attachment(
                &AttachmentRecord {
                    attachment: VaultAttachment {
                        id: attachment_id,
                        parent_item_id: item_id.clone(),
                        display_name: encrypted.display_name,
                        mime_type: encrypted.mime_type,
                        file_size: encrypted.plaintext_size,
                        sha256: encrypted.sha256,
                        created_at_epoch_millis: 1_001,
                    },
                    encrypted_relative_path: encrypted.relative_path,
                },
                1_001,
            )
            .expect("attachment metadata should be stored");
        let service = BackupService::new(repository.clone(), crypto.clone());
        (directory, repository, crypto, service, item_id)
    }

    #[test]
    fn encrypted_entry_binds_manifest_and_path() {
        let key = Zeroizing::new([7_u8; 32]);
        let binding = br#"{"magic":"VaultNoteBackup"}"#;
        let encrypted = encrypt_entry(&key, binding, DATABASE_PATH, b"secret").unwrap();
        assert_eq!(
            &*decrypt_entry(&key, binding, DATABASE_PATH, &encrypted).unwrap(),
            b"secret"
        );
        assert!(decrypt_entry(&key, binding, "attachments/00000001.bin", &encrypted).is_err());
    }

    #[test]
    fn strict_attachment_paths_are_bounded() {
        assert!(is_attachment_path("attachments/00000001.bin"));
        assert!(!is_attachment_path("attachments/../secret.bin"));
        assert!(!is_attachment_path("attachments/000000001.bin"));
    }

    #[test]
    fn password_derivation_matches_android_jdk17_pbe_key_spec() {
        let key = derive_key("pässword-🔐-x", &[3_u8; 32]);
        assert_eq!(
            key.as_ref(),
            &[
                0x7e, 0x48, 0x34, 0x22, 0x3e, 0xd3, 0x56, 0x8e, 0xc0, 0x03, 0xd1, 0x8e, 0x30, 0x6c,
                0x64, 0xf4, 0x3b, 0xb8, 0x78, 0x01, 0x86, 0x53, 0xd8, 0x53, 0xd4, 0x65, 0x4d, 0xa6,
                0x8c, 0xac, 0xfa, 0x65,
            ]
        );
    }

    #[test]
    fn encrypted_backup_rejects_wrong_password_then_restores_attachment() {
        let (directory, repository, crypto, service, original_id) = populated_service();
        let backup = directory.path().join("encrypted.vnb");
        let summary = service
            .export_to(TEST_PASSWORD, 2_000, backup.clone())
            .expect("encrypted backup should export");
        assert_eq!(summary.item_count, 1);
        assert_eq!(summary.attachment_count, 1);
        assert_eq!(
            service
                .inspect(&backup)
                .expect("backup should inspect")
                .protection,
            BackupProtection::Encrypted
        );
        assert!(
            service
                .restore_auto(Some("incorrect password value"), &backup)
                .is_err()
        );
        assert_eq!(
            repository
                .list_items(VaultSection::Active, 100)
                .expect("notes should list")
                .len(),
            1
        );

        let restored = service
            .restore_auto(Some(TEST_PASSWORD), &backup)
            .expect("correct password should restore");
        assert_eq!(restored.restored_item_count, 1);
        assert_eq!(restored.restored_attachment_count, 1);
        let restored_item = repository
            .list_items(VaultSection::Active, 100)
            .expect("notes should list")
            .into_iter()
            .find(|item| item.id != original_id)
            .expect("restored copy should exist");
        let attachment = repository
            .attachment_record(
                &repository
                    .list_attachments(&restored_item.id)
                    .expect("attachments should list")[0]
                    .id,
            )
            .expect("restored attachment should load");
        assert_eq!(
            crypto
                .decrypt_bytes(
                    &attachment.encrypted_relative_path,
                    &attachment.attachment.id,
                )
                .expect("attachment should authenticate")
                .as_slice(),
            b"portable attachment payload"
        );
    }

    #[test]
    fn plaintext_backup_is_explicit_and_detects_tampered_content() {
        let (directory, repository, _crypto, service, _original_id) = populated_service();
        let backup = directory.path().join("readable.vnb");
        service
            .export_plaintext_to(3_000, backup.clone())
            .expect("plaintext backup should export");
        assert_eq!(
            service
                .inspect(&backup)
                .expect("backup should inspect")
                .protection,
            BackupProtection::Plaintext
        );
        let restored = service
            .restore_auto(None, &backup)
            .expect("confirmed plaintext backup should restore");
        assert_eq!(restored.restored_item_count, 1);
        assert_eq!(restored.restored_attachment_count, 1);

        let mut archive_bytes = fs::read(&backup).expect("backup should be readable");
        let needle = b"portable attachment payload";
        let offset = archive_bytes
            .windows(needle.len())
            .position(|window| window == needle)
            .expect("stored plaintext attachment should be present");
        archive_bytes[offset] ^= 0x40;
        fs::write(&backup, archive_bytes).expect("tampered fixture should be written");

        assert!(service.restore_auto(None, &backup).is_err());
        assert_eq!(
            repository
                .list_items(VaultSection::Active, 100)
                .expect("notes should list")
                .len(),
            2,
            "validation failure must not partially restore notes"
        );
    }

    #[test]
    fn database_json_field_order_matches_android_streaming_reader() {
        let (_directory, repository, _crypto, _service, _original_id) = populated_service();
        let database = database_from_snapshot(
            &repository
                .portable_snapshot()
                .expect("snapshot should be available"),
        )
        .expect("database model should be created");
        let json = serde_json::to_string(&database).expect("database should serialize");
        let ordered_fields = [
            "\"schemaVersion\"",
            "\"itemCount\"",
            "\"attachmentCount\"",
            "\"items\"",
            "\"tags\"",
            "\"itemTags\"",
            "\"datedEntries\"",
            "\"datedEntryAlerts\"",
            "\"attachments\"",
        ];
        let mut previous = 0;
        for field in ordered_fields {
            let position = json[previous..]
                .find(field)
                .map(|offset| previous + offset)
                .expect("Android field should be present");
            assert!(position >= previous);
            previous = position + field.len();
        }
        let body = json.find("\"body\"").expect("body should exist");
        let body_document = json
            .find("\"bodyDocument\"")
            .expect("body document should exist");
        let ocr = json.find("\"ocrText\"").expect("OCR should exist");
        assert!(body < body_document && body_document < ocr);
    }
}
