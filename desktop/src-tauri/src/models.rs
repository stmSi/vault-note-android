use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VaultSection {
    Active,
    Archived,
    Trash,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct VaultItemSummary {
    pub id: String,
    pub item_type: String,
    pub color: String,
    pub title: String,
    pub body_preview: String,
    pub is_pinned: bool,
    pub is_favorite: bool,
    pub is_archived: bool,
    pub created_at_epoch_millis: i64,
    pub updated_at_epoch_millis: i64,
    pub sync_status: String,
    pub deleted_at_epoch_millis: Option<i64>,
    pub next_dated_entry_at_epoch_millis: Option<i64>,
    pub has_overdue_entry: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct VaultNote {
    pub id: String,
    pub title: String,
    pub body: String,
    pub color: String,
    pub is_pinned: bool,
    pub is_favorite: bool,
    pub is_archived: bool,
    pub created_at_epoch_millis: i64,
    pub updated_at_epoch_millis: i64,
    pub local_revision: i64,
    pub remote_revision: Option<i64>,
    pub last_synced_revision: Option<i64>,
    pub sync_status: String,
    pub deleted_at_epoch_millis: Option<i64>,
    pub body_document: Option<NoteBodyDocument>,
    pub dated_entries: Vec<DatedEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NoteBodyDocument {
    pub version: u32,
    pub blocks: Vec<NoteBlock>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NoteBlock {
    pub id: String,
    #[serde(rename = "type")]
    pub block_type: String,
    pub text: String,
    pub checked: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DatedEntryAlert {
    pub id: String,
    pub lead_time_minutes: i64,
    pub snoozed_until_epoch_millis: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DatedEntry {
    pub id: String,
    pub item_id: String,
    #[serde(rename = "type")]
    pub entry_type: String,
    pub label: String,
    pub occurrence_at_epoch_millis: i64,
    pub is_all_day: bool,
    pub time_zone_id: String,
    pub recurrence_unit: Option<String>,
    pub recurrence_interval: Option<i64>,
    pub completed_at_epoch_millis: Option<i64>,
    pub created_at_epoch_millis: i64,
    pub updated_at_epoch_millis: i64,
    pub alerts: Vec<DatedEntryAlert>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DatedEntryDraft {
    pub id: Option<String>,
    #[serde(rename = "type")]
    pub entry_type: String,
    pub label: String,
    pub occurrence_at_epoch_millis: i64,
    pub is_all_day: bool,
    pub time_zone_id: String,
    pub recurrence_unit: Option<String>,
    pub recurrence_interval: Option<i64>,
    pub alert_lead_times_minutes: Vec<i64>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AgendaEntry {
    pub entry: DatedEntry,
    pub note_title: String,
    pub is_archived: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ScheduledAlert {
    pub notification_id: i32,
    pub alert_id: String,
    pub entry_id: String,
    pub item_id: String,
    pub trigger_at_epoch_millis: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub item: VaultItemSummary,
    pub snippet: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncQueueStatus {
    pub pending_count: i64,
    pub running_count: i64,
    pub retry_count: i64,
    pub failed_count: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AuthStatus {
    pub setup_required: bool,
    pub unlocked: bool,
    pub encryption_mode: VaultEncryptionMode,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum VaultEncryptionMode {
    Unconfigured,
    Password,
    Unencrypted,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct VaultAttachment {
    pub id: String,
    pub parent_item_id: String,
    pub display_name: String,
    pub mime_type: String,
    pub file_size: i64,
    pub sha256: String,
    pub created_at_epoch_millis: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentRecord {
    pub attachment: VaultAttachment,
    pub encrypted_relative_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortableItem {
    pub id: String,
    pub item_type: String,
    pub color: String,
    pub title: String,
    pub body: String,
    pub ocr_text: String,
    pub is_pinned: bool,
    pub is_favorite: bool,
    pub is_archived: bool,
    pub sort_position: i64,
    pub created_at: i64,
    pub updated_at: i64,
    pub local_revision: i64,
    pub deleted_at: Option<i64>,
    pub conflict_origin_id: Option<String>,
    pub body_document_json: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortableTag {
    pub id: String,
    pub name: String,
    pub normalized_name: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortableItemTag {
    pub item_id: String,
    pub tag_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortableAttachment {
    pub record: AttachmentRecord,
    pub image_width: Option<i64>,
    pub image_height: Option<i64>,
    pub pdf_page_count: Option<i64>,
    pub ocr_state: String,
    pub ocr_text: String,
    pub ocr_source_checksum: Option<String>,
    pub ocr_failure_code: Option<String>,
    pub ocr_updated_at: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortableDatedEntry {
    pub entry: DatedEntry,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortableSnapshot {
    pub items: Vec<PortableItem>,
    pub tags: Vec<PortableTag>,
    pub item_tags: Vec<PortableItemTag>,
    pub attachments: Vec<PortableAttachment>,
    pub dated_entries: Vec<PortableDatedEntry>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BackupSummary {
    pub item_count: usize,
    pub attachment_count: usize,
    pub created_at_epoch_millis: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RestoreSummary {
    pub restored_item_count: usize,
    pub restored_attachment_count: usize,
}
