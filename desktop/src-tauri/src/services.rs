use std::{
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use crate::{
    backup::BackupService,
    crypto::AttachmentCrypto,
    error::AppError,
    models::{
        AttachmentRecord, SearchResult, SyncQueueStatus, SyncReport, VaultAttachment,
        VaultItemSummary, VaultNote,
    },
    repository::VaultRepository,
    validation::{compile_search_query, parse_section, validate_id, validate_limit, validate_note},
};

#[derive(Clone)]
pub struct VaultService {
    repository: Arc<dyn VaultRepository>,
}

impl VaultService {
    pub fn new(repository: Arc<dyn VaultRepository>) -> Self {
        Self { repository }
    }

    pub fn list_items(
        &self,
        section: &str,
        limit: usize,
    ) -> Result<Vec<VaultItemSummary>, AppError> {
        self.repository
            .list_items(parse_section(section)?, validate_limit(limit)?)
    }

    pub fn get_note(&self, id: &str) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        self.repository.get_note(id)
    }

    pub fn create_note(&self) -> Result<VaultNote, AppError> {
        let now = now_epoch_millis()?;
        let id = self.repository.create_note("", "", now)?;
        self.repository.get_note(&id)
    }

    pub fn save_note(&self, id: &str, title: &str, body: &str) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        validate_note(title, body)?;
        self.repository
            .save_note(id, title, body, now_epoch_millis()?)?;
        self.repository.get_note(id)
    }

    pub fn set_pinned(&self, id: &str, value: bool) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        self.repository.set_pinned(id, value, now_epoch_millis()?)?;
        self.repository.get_note(id)
    }

    pub fn set_favorite(&self, id: &str, value: bool) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        self.repository
            .set_favorite(id, value, now_epoch_millis()?)?;
        self.repository.get_note(id)
    }

    pub fn set_archived(&self, id: &str, value: bool) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        self.repository
            .set_archived(id, value, now_epoch_millis()?)?;
        self.repository.get_note(id)
    }

    pub fn move_to_trash(&self, id: &str) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        self.repository.move_to_trash(id, now_epoch_millis()?)?;
        self.repository.get_note(id)
    }

    pub fn restore(&self, id: &str) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        self.repository.restore(id, now_epoch_millis()?)?;
        self.repository.get_note(id)
    }

    pub fn search(&self, query: &str, limit: usize) -> Result<Vec<SearchResult>, AppError> {
        let limit = validate_limit(limit)?;
        let Some(expression) = compile_search_query(query)? else {
            return Ok(Vec::new());
        };
        self.repository.search(&expression, limit)
    }

    pub fn sync_queue_status(&self) -> Result<SyncQueueStatus, AppError> {
        self.repository.sync_queue_status()
    }
}

#[derive(Clone)]
pub struct FakeSyncService {
    repository: Arc<dyn VaultRepository>,
}

impl FakeSyncService {
    pub fn new(repository: Arc<dyn VaultRepository>) -> Self {
        Self { repository }
    }

    pub fn run_once(&self) -> Result<SyncReport, AppError> {
        self.repository.process_fake_sync(now_epoch_millis()?, 100)
    }
}

pub struct AppState {
    pub vault: VaultService,
    pub sync: FakeSyncService,
    pub attachments: AttachmentService,
    pub backup: BackupService,
}

impl AppState {
    pub fn new(repository: Arc<dyn VaultRepository>, attachment_crypto: AttachmentCrypto) -> Self {
        Self {
            vault: VaultService::new(Arc::clone(&repository)),
            sync: FakeSyncService::new(Arc::clone(&repository)),
            attachments: AttachmentService::new(Arc::clone(&repository), attachment_crypto.clone()),
            backup: BackupService::new(repository, attachment_crypto),
        }
    }
}

#[derive(Clone)]
pub struct AttachmentService {
    repository: Arc<dyn VaultRepository>,
    crypto: AttachmentCrypto,
}

impl AttachmentService {
    pub fn new(repository: Arc<dyn VaultRepository>, crypto: AttachmentCrypto) -> Self {
        Self { repository, crypto }
    }

    pub fn list(&self, parent_item_id: &str) -> Result<Vec<VaultAttachment>, AppError> {
        validate_id(parent_item_id)?;
        self.repository.list_attachments(parent_item_id)
    }

    pub fn import_from(
        &self,
        parent_item_id: &str,
        source: &std::path::Path,
    ) -> Result<VaultAttachment, AppError> {
        validate_id(parent_item_id)?;
        let id = uuid::Uuid::new_v4().hyphenated().to_string();
        let encrypted = self.crypto.encrypt_import(source, &id)?;
        let now = now_epoch_millis()?;
        let record = AttachmentRecord {
            attachment: VaultAttachment {
                id: id.clone(),
                parent_item_id: parent_item_id.to_owned(),
                display_name: encrypted.display_name,
                mime_type: encrypted.mime_type,
                file_size: encrypted.plaintext_size,
                sha256: encrypted.sha256,
                created_at_epoch_millis: now,
            },
            encrypted_relative_path: encrypted.relative_path,
        };
        match self.repository.add_attachment(&record, now) {
            Ok(attachment) => Ok(attachment),
            Err(error) => {
                let _ = self
                    .crypto
                    .remove(&record.encrypted_relative_path, &record.attachment.id);
                Err(error)
            }
        }
    }

    pub fn export_filename(&self, id: &str) -> Result<String, AppError> {
        validate_id(id)?;
        let record = self.repository.attachment_record(id)?;
        Ok(record.attachment.display_name)
    }

    pub fn export_to(&self, id: &str, destination: &std::path::Path) -> Result<(), AppError> {
        validate_id(id)?;
        let record = self.repository.attachment_record(id)?;
        self.crypto.export_to(
            &record.encrypted_relative_path,
            &record.attachment.id,
            destination,
        )?;
        Ok(())
    }

    pub fn delete(&self, id: &str) -> Result<(), AppError> {
        validate_id(id)?;
        let record = self.repository.attachment_record(id)?;
        let staged = self
            .crypto
            .stage_removal(&record.encrypted_relative_path, &record.attachment.id)?;
        match self.repository.delete_attachment(id, now_epoch_millis()?) {
            Ok(_) => staged.commit(),
            Err(error) => {
                staged.rollback()?;
                Err(error)
            }
        }
    }
}

pub(crate) fn now_epoch_millis() -> Result<i64, AppError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| AppError::Clock)?;
    i64::try_from(duration.as_millis()).map_err(|_| AppError::Clock)
}
