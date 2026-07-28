use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ItemMutationRequest {
    pub expected_version_token: Option<String>,
    pub encrypted_payload: String,
    pub ciphertext_sha256: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeleteMutationRequest {
    pub expected_version_token: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MutationOutcome {
    Applied,
    Conflict,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MutationResponse {
    pub outcome: MutationOutcome,
    pub server_revision: Option<i64>,
    pub version_token: Option<String>,
    pub remote: Option<RemoteItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteItem {
    pub item_id: String,
    pub server_revision: i64,
    pub version_token: String,
    pub deleted: bool,
    pub encrypted_payload: Option<String>,
    pub ciphertext_sha256: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ChangePage {
    pub changes: Vec<RemoteItem>,
    pub next_cursor: Option<String>,
    pub has_more: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayTlsIdentity {
    pub dns_name: String,
    pub certificate_sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayDiscovery {
    pub service_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RelayKeyDerivation {
    pub algorithm: String,
    pub iterations: u32,
    pub salt: String,
    pub key_bits: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentReceipt {
    pub attachment_id: String,
    pub ciphertext_sha256: String,
    pub ciphertext_size: u64,
    pub remote_path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentDeleteResponse {
    pub attachment_id: String,
    pub deleted: bool,
}

#[derive(Debug, Clone)]
pub struct StoredMutation {
    pub status_code: u16,
    pub response: MutationResponse,
}
