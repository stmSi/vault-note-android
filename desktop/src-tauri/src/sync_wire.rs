use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 3;
pub const ITEM_SCHEMA_VERSION: u32 = 3;
pub const MAX_ITEM_PLAINTEXT_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_ATTACHMENT_PLAINTEXT_BYTES: u64 = 100 * 1024 * 1024;
pub const MAX_CHANGE_PAGE_ITEMS: usize = 200;

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayInformation {
    pub protocol_version: u32,
    pub minimum_client_protocol_version: u32,
    pub vault_id: String,
    pub tls_identity: RelayTlsIdentity,
    pub discovery: RelayDiscovery,
    pub key_derivation: RelayKeyDerivation,
    pub limits: RelayLimits,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayTlsIdentity {
    pub dns_name: String,
    pub certificate_sha256: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayDiscovery {
    pub service_type: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayKeyDerivation {
    pub algorithm: String,
    pub iterations: u32,
    pub salt: String,
    pub key_bits: u16,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayLimits {
    pub maximum_item_envelope_bytes: usize,
    pub maximum_attachment_envelope_bytes: u64,
    pub maximum_change_page_size: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KeyCheckEnvelope {
    pub encrypted_key_check: String,
    pub ciphertext_sha256: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ItemMutationRequest<'a> {
    pub expected_version_token: Option<&'a str>,
    pub encrypted_payload: &'a str,
    pub ciphertext_sha256: &'a str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteMutationRequest<'a> {
    pub expected_version_token: Option<&'a str>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MutationOutcome {
    Applied,
    Conflict,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct MutationResponse {
    pub outcome: MutationOutcome,
    pub server_revision: Option<i64>,
    pub version_token: Option<String>,
    pub remote: Option<RemoteItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RemoteItem {
    pub item_id: String,
    pub server_revision: i64,
    pub version_token: String,
    pub deleted: bool,
    pub encrypted_payload: Option<String>,
    pub ciphertext_sha256: Option<String>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ChangePage {
    pub changes: Vec<RemoteItem>,
    pub next_cursor: Option<String>,
    pub has_more: bool,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentReceipt {
    pub attachment_id: String,
    pub ciphertext_sha256: String,
    pub ciphertext_size: u64,
    pub remote_path: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorBody {
    pub code: String,
    pub retryable: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ItemMetadata {
    pub schema_version: u32,
    pub id: String,
    #[serde(rename = "type")]
    pub item_type: String,
    pub title: String,
    pub body: String,
    pub ocr_text: String,
    pub color: String,
    pub is_pinned: bool,
    pub is_favorite: bool,
    pub is_archived: bool,
    pub sort_position: i64,
    pub created_at: i64,
    pub updated_at: i64,
    pub client_revision: i64,
    pub body_document_json: Option<String>,
    pub tags: Vec<String>,
    pub attachments: Vec<RemoteAttachment>,
    pub dated_entries: Vec<RemoteDatedEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteAttachment {
    pub id: String,
    pub remote_path: String,
    pub original_filename: String,
    pub mime_type: String,
    pub file_size_bytes: i64,
    pub plaintext_sha256: String,
    pub encryption_format_version: i64,
    pub image_width: Option<i64>,
    pub image_height: Option<i64>,
    pub pdf_page_count: Option<i64>,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteDatedEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub entry_type: String,
    pub label: String,
    pub occurrence_at: i64,
    pub is_all_day: bool,
    pub time_zone_id: String,
    pub recurrence_unit: Option<String>,
    pub recurrence_interval: Option<i64>,
    pub completed_at: Option<i64>,
    pub created_at: i64,
    pub updated_at: i64,
    pub alert_lead_times_minutes: Vec<i64>,
}

impl ItemMetadata {
    pub fn validate(&self, expected_item_id: &str) -> bool {
        self.schema_version == ITEM_SCHEMA_VERSION
            && self.id == expected_item_id
            && valid_id(&self.id)
            && matches!(
                self.item_type.as_str(),
                "NOTE" | "DOCUMENT" | "IMAGE" | "LINK"
            )
            && matches!(
                self.color.as_str(),
                "DEFAULT" | "RED" | "ORANGE" | "YELLOW" | "GREEN" | "BLUE" | "PURPLE"
            )
            && self.title.chars().count() <= 1_500_000
            && self.body.chars().count() <= 1_500_000
            && self.ocr_text.chars().count() <= 1_500_000
            && self.client_revision > 0
            && self.tags.len() <= 64
            && self.tags.iter().all(|tag| tag.chars().count() <= 256)
            && self.attachments.len() <= 512
            && unique_ids(self.attachments.iter().map(|value| value.id.as_str()))
            && self.attachments.iter().all(RemoteAttachment::is_valid)
            && self.dated_entries.len() <= 512
            && unique_ids(self.dated_entries.iter().map(|value| value.id.as_str()))
            && self.dated_entries.iter().all(RemoteDatedEntry::is_valid)
    }
}

impl RemoteAttachment {
    fn is_valid(&self) -> bool {
        valid_id(&self.id)
            && self.remote_path == format!("/v1/attachments/{}", self.id)
            && !self.original_filename.trim().is_empty()
            && self.original_filename.chars().count() <= 512
            && !self.original_filename.contains(['/', '\\'])
            && !self.original_filename.chars().any(char::is_control)
            && !self.mime_type.is_empty()
            && self.mime_type.len() <= 256
            && (0..=MAX_ATTACHMENT_PLAINTEXT_BYTES as i64).contains(&self.file_size_bytes)
            && lower_hex_sha256(&self.plaintext_sha256)
            && self.encryption_format_version > 0
            && self
                .image_width
                .is_none_or(|value| (1..=100_000).contains(&value))
            && self
                .image_height
                .is_none_or(|value| (1..=100_000).contains(&value))
            && self
                .pdf_page_count
                .is_none_or(|value| (1..=1_000_000).contains(&value))
    }
}

impl RemoteDatedEntry {
    fn is_valid(&self) -> bool {
        valid_id(&self.id)
            && matches!(
                self.entry_type.as_str(),
                "REMINDER" | "DEADLINE" | "IMPORTANT_DATE" | "RENEWAL"
            )
            && self.label.chars().count() <= 1_024
            && self.occurrence_at >= 0
            && !self.time_zone_id.is_empty()
            && self.time_zone_id.len() <= 128
            && self
                .recurrence_unit
                .as_deref()
                .is_none_or(|value| matches!(value, "DAY" | "WEEK" | "MONTH" | "YEAR"))
            && self
                .recurrence_interval
                .is_none_or(|value| (1..=999).contains(&value))
            && self.recurrence_unit.is_some() == self.recurrence_interval.is_some()
            && self
                .alert_lead_times_minutes
                .iter()
                .all(|value| (0..=5_256_000).contains(value))
    }
}

pub fn valid_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || value == b'_' || value == b'-')
}

pub fn lower_hex_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|value| value.is_ascii_digit() || (b'a'..=b'f').contains(&value))
}

fn unique_ids<'a>(mut values: impl Iterator<Item = &'a str>) -> bool {
    let mut seen = std::collections::HashSet::new();
    values.all(|value| valid_id(value) && seen.insert(value))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn metadata_json_matches_android_field_names() {
        let metadata = ItemMetadata {
            schema_version: ITEM_SCHEMA_VERSION,
            id: "item_1".to_owned(),
            item_type: "NOTE".to_owned(),
            title: "Travel".to_owned(),
            body: "Bangkok".to_owned(),
            ocr_text: String::new(),
            color: "BLUE".to_owned(),
            is_pinned: true,
            is_favorite: false,
            is_archived: false,
            sort_position: 42,
            created_at: 1,
            updated_at: 2,
            client_revision: 3,
            body_document_json: None,
            tags: vec!["trip".to_owned()],
            attachments: Vec::new(),
            dated_entries: Vec::new(),
        };
        let encoded = serde_json::to_vec(&metadata).expect("metadata should encode");
        let json = String::from_utf8(encoded.clone()).expect("metadata should be UTF-8");
        assert!(json.contains("\"schemaVersion\":3"));
        assert!(json.contains("\"sortPosition\":42"));
        assert!(json.contains("\"bodyDocumentJson\":null"));
        let decoded: ItemMetadata =
            serde_json::from_slice(&encoded).expect("metadata should decode");
        assert!(decoded.validate("item_1"));
        assert_eq!(decoded, metadata);
    }

    #[test]
    fn metadata_rejects_attachment_path_rebinding() {
        let attachment = RemoteAttachment {
            id: "attachment_1".to_owned(),
            remote_path: "/v1/attachments/attachment_2".to_owned(),
            original_filename: "paper.pdf".to_owned(),
            mime_type: "application/pdf".to_owned(),
            file_size_bytes: 10,
            plaintext_sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                .to_owned(),
            encryption_format_version: 3,
            image_width: None,
            image_height: None,
            pdf_page_count: Some(1),
            created_at: 1,
        };
        assert!(!attachment.is_valid());
    }
}
