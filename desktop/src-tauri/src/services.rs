use std::{
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use chrono::{DateTime, Days, Utc};
use chrono_tz::Tz;

use crate::{
    backup::BackupService,
    crypto::AttachmentCrypto,
    error::AppError,
    models::{
        AgendaEntry, AttachmentRecord, DatedEntryDraft, NoteBodyDocument, ScheduledAlert,
        SearchResult, SyncQueueStatus, SyncReport, VaultAttachment, VaultItemSummary, VaultNote,
    },
    repository::VaultRepository,
    validation::{
        compile_search_query, parse_section, validate_dated_entry, validate_id, validate_limit,
        validate_note, validate_note_document,
    },
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

    pub fn save_structured_note(
        &self,
        id: &str,
        title: &str,
        document: &NoteBodyDocument,
    ) -> Result<VaultNote, AppError> {
        validate_id(id)?;
        let body = validate_note_document(title, document)?;
        let document_json = serde_json::to_string(document).map_err(|_| AppError::InvalidState)?;
        self.repository.save_structured_note(
            id,
            title,
            &body,
            &document_json,
            now_epoch_millis()?,
        )?;
        self.repository.get_note(id)
    }

    pub fn save_dated_entry(
        &self,
        item_id: &str,
        draft: &DatedEntryDraft,
    ) -> Result<VaultNote, AppError> {
        validate_id(item_id)?;
        validate_dated_entry(draft)?;
        self.repository
            .save_dated_entry(item_id, draft, now_epoch_millis()?)?;
        self.repository.get_note(item_id)
    }

    pub fn delete_dated_entry(&self, entry_id: &str) -> Result<(), AppError> {
        validate_id(entry_id)?;
        self.repository
            .delete_dated_entry(entry_id, now_epoch_millis()?)
    }

    pub fn complete_dated_entry(&self, entry_id: &str) -> Result<(), AppError> {
        validate_id(entry_id)?;
        self.repository
            .complete_dated_entry(entry_id, now_epoch_millis()?)
    }

    pub fn snooze_dated_entry(&self, entry_id: &str, minutes: i64) -> Result<(), AppError> {
        validate_id(entry_id)?;
        if !(1..=10_080).contains(&minutes) {
            return Err(AppError::InvalidInput {
                field: "date",
                reason: "invalid snooze duration".to_owned(),
            });
        }
        let until = now_epoch_millis()?
            .checked_add(minutes.checked_mul(60_000).ok_or(AppError::InvalidState)?)
            .ok_or(AppError::InvalidState)?;
        self.repository.snooze_dated_entry(entry_id, until)
    }

    pub fn list_agenda(
        &self,
        include_completed: bool,
        limit: usize,
    ) -> Result<Vec<AgendaEntry>, AppError> {
        self.repository
            .list_agenda(include_completed, validate_limit(limit)?)
    }

    pub fn scheduled_alerts(&self) -> Result<Vec<ScheduledAlert>, AppError> {
        self.repository
            .scheduled_alerts(now_epoch_millis()?, 10_000)
    }

    pub fn calendar_export(&self, entry_id: &str) -> Result<(String, String), AppError> {
        validate_id(entry_id)?;
        let row = self.repository.get_dated_entry(entry_id)?;
        calendar_export(&row, now_epoch_millis()?)
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

fn calendar_export(row: &AgendaEntry, now: i64) -> Result<(String, String), AppError> {
    let entry = &row.entry;
    let instant = DateTime::<Utc>::from_timestamp_millis(entry.occurrence_at_epoch_millis)
        .ok_or(AppError::InvalidState)?;
    let summary = if entry.label.trim().is_empty() {
        if row.note_title.trim().is_empty() {
            "VaultNote date"
        } else {
            row.note_title.trim()
        }
    } else {
        entry.label.trim()
    };
    let (start, end) = if entry.is_all_day {
        let timezone: Tz = entry
            .time_zone_id
            .parse()
            .map_err(|_| AppError::InvalidState)?;
        let date = instant.with_timezone(&timezone).date_naive();
        let next = date
            .checked_add_days(Days::new(1))
            .ok_or(AppError::InvalidState)?;
        (
            format!("DTSTART;VALUE=DATE:{}", date.format("%Y%m%d")),
            format!("DTEND;VALUE=DATE:{}", next.format("%Y%m%d")),
        )
    } else {
        (
            format!("DTSTART:{}", instant.format("%Y%m%dT%H%M%SZ")),
            String::new(),
        )
    };
    let recurrence = entry
        .recurrence_unit
        .as_deref()
        .zip(entry.recurrence_interval)
        .map(|(unit, interval)| {
            let frequency = match unit {
                "DAY" => "DAILY",
                "WEEK" => "WEEKLY",
                "MONTH" => "MONTHLY",
                "YEAR" => "YEARLY",
                _ => return Err(AppError::InvalidState),
            };
            Ok(format!("RRULE:FREQ={frequency};INTERVAL={interval}"))
        })
        .transpose()?;
    let mut lines = vec![
        "BEGIN:VCALENDAR".to_owned(),
        "VERSION:2.0".to_owned(),
        "PRODID:-//VaultNote//Private dates//EN".to_owned(),
        "CALSCALE:GREGORIAN".to_owned(),
        "BEGIN:VEVENT".to_owned(),
        format!("UID:{}@vaultnote.local", entry.id),
        format!(
            "DTSTAMP:{}",
            DateTime::<Utc>::from_timestamp_millis(now)
                .ok_or(AppError::InvalidState)?
                .format("%Y%m%dT%H%M%SZ")
        ),
        start,
    ];
    if !end.is_empty() {
        lines.push(end);
    }
    lines.push(format!("SUMMARY:{}", escape_icalendar(summary)));
    lines.push(format!(
        "DESCRIPTION:{}",
        escape_icalendar("Exported from VaultNote")
    ));
    if let Some(recurrence) = recurrence {
        lines.push(recurrence);
    }
    lines.extend([
        "END:VEVENT".to_owned(),
        "END:VCALENDAR".to_owned(),
        String::new(),
    ]);
    let stem: String = summary
        .chars()
        .filter(|character| character.is_alphanumeric() || matches!(character, ' ' | '-' | '_'))
        .take(80)
        .collect();
    let filename = format!(
        "{}.ics",
        if stem.trim().is_empty() {
            "VaultNote-date"
        } else {
            stem.trim()
        }
    );
    Ok((filename, lines.join("\r\n")))
}

fn escape_icalendar(value: &str) -> String {
    value
        .replace('\\', "\\\\")
        .replace('\n', "\\n")
        .replace(',', "\\,")
        .replace(';', "\\;")
}
