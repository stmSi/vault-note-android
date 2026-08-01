use serde::Deserialize;
use std::path::PathBuf;
use tauri::{State, ipc::Response};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::{
    embedded_relay::{EmbeddedRelayStart, EmbeddedRelayStatus},
    error::CommandError,
    models::{
        AgendaEntry, AuthStatus, BackupSummary, DatedEntryDraft, NoteBodyDocument, RestoreSummary,
        ScheduledAlert, SearchResult, SyncQueueStatus, VaultAttachment, VaultItemSummary,
        VaultNote,
    },
    nearby_pairing::PendingNearbyPairing,
    runtime::RuntimeState,
    sync_engine::{PairRelayParameters, SyncConnectionStatus, SyncRunReport},
    validation::{validate_id, validate_password},
};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ListItemsRequest {
    section: String,
    limit: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ItemRequest {
    id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SaveNoteRequest {
    id: String,
    title: String,
    body: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SaveStructuredNoteRequest {
    id: String,
    title: String,
    body_document: NoteBodyDocument,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SaveDatedEntryRequest {
    item_id: String,
    draft: DatedEntryDraft,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AgendaRequest {
    include_completed: bool,
    limit: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SnoozeRequest {
    id: String,
    minutes: i64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ItemFlagRequest {
    id: String,
    value: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SearchNotesRequest {
    query: String,
    limit: usize,
}

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PasswordRequest {
    password: String,
}

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PairRelayRequest {
    host_address: String,
    port: u16,
    certificate_sha256: String,
    authentication_token: String,
    sync_password: String,
    expected_vault_id: Option<String>,
    fingerprint_confirmed: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ImportAttachmentPathRequest {
    id: String,
    path: PathBuf,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BackupPathRequest {
    path: PathBuf,
}

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RestoreBackupPathRequest {
    #[zeroize(skip)]
    path: PathBuf,
    password: Option<String>,
    plaintext_confirmed: bool,
}

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EmbeddedRelayAccessRequest {
    password: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NearbyPairingRequest {
    request_id: String,
}

#[derive(serde::Serialize, Zeroize, ZeroizeOnDrop)]
#[serde(rename_all = "camelCase")]
pub struct EmbeddedRelayPairingDetails {
    #[zeroize(skip)]
    status: EmbeddedRelayStatus,
    authentication_token: String,
}

#[tauri::command]
pub fn list_items(
    state: State<'_, RuntimeState>,
    request: ListItemsRequest,
) -> Result<Vec<VaultItemSummary>, CommandError> {
    state
        .with_services(|services| services.vault.list_items(&request.section, request.limit))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn get_note(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| services.vault.get_note(&request.id))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn create_note(state: State<'_, RuntimeState>) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| services.vault.create_note())
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn save_note(
    state: State<'_, RuntimeState>,
    request: SaveNoteRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| {
            services
                .vault
                .save_note(&request.id, &request.title, &request.body)
        })
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn save_structured_note(
    state: State<'_, RuntimeState>,
    request: SaveStructuredNoteRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| {
            services
                .vault
                .save_structured_note(&request.id, &request.title, &request.body_document)
        })
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn save_dated_entry(
    state: State<'_, RuntimeState>,
    request: SaveDatedEntryRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| {
            services
                .vault
                .save_dated_entry(&request.item_id, &request.draft)
        })
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn delete_dated_entry(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<(), CommandError> {
    state
        .with_services(|services| services.vault.delete_dated_entry(&request.id))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn complete_dated_entry(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<(), CommandError> {
    state
        .with_services(|services| services.vault.complete_dated_entry(&request.id))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn snooze_dated_entry(
    state: State<'_, RuntimeState>,
    request: SnoozeRequest,
) -> Result<(), CommandError> {
    state
        .with_services(|services| {
            services
                .vault
                .snooze_dated_entry(&request.id, request.minutes)
        })
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn list_agenda(
    state: State<'_, RuntimeState>,
    request: AgendaRequest,
) -> Result<Vec<AgendaEntry>, CommandError> {
    state
        .with_services(|services| {
            services
                .vault
                .list_agenda(request.include_completed, request.limit)
        })
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn scheduled_alerts(
    state: State<'_, RuntimeState>,
) -> Result<Vec<ScheduledAlert>, CommandError> {
    state
        .with_services(|services| services.vault.scheduled_alerts())
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn export_calendar_entry(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<bool, CommandError> {
    let (filename, contents) = state
        .with_services(|services| services.vault.calendar_export(&request.id))
        .map_err(CommandError::from)?;
    let selected = rfd::AsyncFileDialog::new()
        .set_title("Export date to calendar")
        .add_filter("Calendar event", &["ics"])
        .set_file_name(filename)
        .save_file()
        .await;
    let Some(selected) = selected else {
        return Ok(false);
    };
    std::fs::write(selected.path(), contents)
        .map(|()| true)
        .map_err(|error| CommandError::from(crate::error::AppError::Storage(error)))
}

#[tauri::command]
pub fn set_pinned(
    state: State<'_, RuntimeState>,
    request: ItemFlagRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| services.vault.set_pinned(&request.id, request.value))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn set_favorite(
    state: State<'_, RuntimeState>,
    request: ItemFlagRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| services.vault.set_favorite(&request.id, request.value))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn set_archived(
    state: State<'_, RuntimeState>,
    request: ItemFlagRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| services.vault.set_archived(&request.id, request.value))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn move_to_trash(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| services.vault.move_to_trash(&request.id))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn restore(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<VaultNote, CommandError> {
    state
        .with_services(|services| services.vault.restore(&request.id))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn search_notes(
    state: State<'_, RuntimeState>,
    request: SearchNotesRequest,
) -> Result<Vec<SearchResult>, CommandError> {
    state
        .with_services(|services| services.vault.search(&request.query, request.limit))
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn sync_queue_status(state: State<'_, RuntimeState>) -> Result<SyncQueueStatus, CommandError> {
    state
        .with_services(|services| services.vault.sync_queue_status())
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn sync_connection_status(
    state: State<'_, RuntimeState>,
) -> Result<SyncConnectionStatus, CommandError> {
    state
        .with_services(|services| services.sync.status())
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn discover_relays(
    state: State<'_, RuntimeState>,
) -> Result<Vec<crate::lan_discovery::DiscoveredRelay>, CommandError> {
    let service = state
        .with_services(|services| Ok(services.sync.clone()))
        .map_err(CommandError::from)?;
    service.discover().await.map_err(CommandError::from)
}

#[tauri::command]
pub async fn pair_relay(
    state: State<'_, RuntimeState>,
    mut request: PairRelayRequest,
) -> Result<SyncConnectionStatus, CommandError> {
    let service = state
        .with_services(|services| Ok(services.sync.clone()))
        .map_err(CommandError::from)?;
    let parameters = PairRelayParameters {
        host_address: request.host_address.clone(),
        port: request.port,
        certificate_sha256: request.certificate_sha256.clone(),
        authentication_token: request.authentication_token.clone(),
        sync_password: request.sync_password.clone(),
        expected_vault_id: request.expected_vault_id.clone(),
        fingerprint_confirmed: request.fingerprint_confirmed,
    };
    request.authentication_token.zeroize();
    request.sync_password.zeroize();
    service.pair(parameters).await.map_err(CommandError::from)
}

#[tauri::command]
pub fn unlock_sync(
    state: State<'_, RuntimeState>,
    mut request: PasswordRequest,
) -> Result<SyncConnectionStatus, CommandError> {
    let result = state
        .with_services(|services| services.sync.unlock_sync(&request.password))
        .map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub fn disconnect_relay(
    state: State<'_, RuntimeState>,
) -> Result<SyncConnectionStatus, CommandError> {
    state
        .with_services(|services| services.sync.disconnect())
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn run_sync(state: State<'_, RuntimeState>) -> Result<SyncRunReport, CommandError> {
    let service = state
        .with_services(|services| Ok(services.sync.clone()))
        .map_err(CommandError::from)?;
    service.run_once().await.map_err(CommandError::from)
}

#[tauri::command]
pub async fn embedded_relay_status(
    state: State<'_, RuntimeState>,
) -> Result<EmbeddedRelayStatus, CommandError> {
    state
        .embedded_relay()
        .start_if_enabled()
        .await
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn enable_embedded_relay(
    state: State<'_, RuntimeState>,
    mut request: EmbeddedRelayAccessRequest,
) -> Result<EmbeddedRelayStatus, CommandError> {
    let result = async {
        validate_sync_password(&request.password)?;
        let started = state.embedded_relay().enable().await?;
        pair_with_embedded_relay(&state, started, &request.password)
            .await
            .map(|details| details.status.clone())
    }
    .await
    .map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub async fn embedded_relay_pairing_details(
    state: State<'_, RuntimeState>,
    mut request: EmbeddedRelayAccessRequest,
) -> Result<EmbeddedRelayPairingDetails, CommandError> {
    let result = async {
        let status = state.embedded_relay().start_if_enabled().await?;
        if !status.enabled || !status.running {
            return Err(crate::error::AppError::SyncNotConfigured);
        }
        let sync = state.with_services(|services| Ok(services.sync.clone()))?;
        let token = token_for_embedded_relay(&sync, &status, &request.password)?;
        Ok(EmbeddedRelayPairingDetails {
            status,
            authentication_token: token.to_string(),
        })
    }
    .await
    .map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub async fn reset_embedded_relay_access(
    state: State<'_, RuntimeState>,
    mut request: EmbeddedRelayAccessRequest,
) -> Result<EmbeddedRelayPairingDetails, CommandError> {
    let result = async {
        validate_sync_password(&request.password)?;
        let started = state.embedded_relay().rotate_access().await?;
        pair_with_embedded_relay(&state, started, &request.password).await
    }
    .await
    .map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub async fn pending_nearby_pairings(
    state: State<'_, RuntimeState>,
) -> Result<Vec<PendingNearbyPairing>, CommandError> {
    state
        .embedded_relay()
        .pending_pairings()
        .await
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn approve_nearby_pairing(
    state: State<'_, RuntimeState>,
    request: NearbyPairingRequest,
) -> Result<(), CommandError> {
    let host = state.embedded_relay();
    let status = host.start_if_enabled().await.map_err(CommandError::from)?;
    let vault_id = status
        .vault_id
        .as_deref()
        .ok_or(crate::error::AppError::EmbeddedRelayUnavailable)
        .map_err(CommandError::from)?;
    let fingerprint = status
        .certificate_sha256
        .as_deref()
        .ok_or(crate::error::AppError::EmbeddedRelayUnavailable)
        .map_err(CommandError::from)?;
    let secret = state
        .with_services(|services| {
            services
                .sync
                .nearby_pairing_secret_for(vault_id, fingerprint)
        })
        .map_err(CommandError::from)?;
    host.approve_pairing(&request.request_id, &secret)
        .await
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn reject_nearby_pairing(
    state: State<'_, RuntimeState>,
    request: NearbyPairingRequest,
) -> Result<(), CommandError> {
    state
        .embedded_relay()
        .reject_pairing(&request.request_id)
        .await
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn auth_status(state: State<'_, RuntimeState>) -> Result<AuthStatus, CommandError> {
    state.status().map_err(CommandError::from)
}

#[tauri::command]
pub fn initialize_vault(
    state: State<'_, RuntimeState>,
    mut request: PasswordRequest,
) -> Result<AuthStatus, CommandError> {
    let result = state
        .initialize(&request.password)
        .map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub fn initialize_unencrypted_vault(
    state: State<'_, RuntimeState>,
) -> Result<AuthStatus, CommandError> {
    state.initialize_unencrypted().map_err(CommandError::from)
}

#[tauri::command]
pub fn unlock(
    state: State<'_, RuntimeState>,
    mut request: PasswordRequest,
) -> Result<AuthStatus, CommandError> {
    let result = state.unlock(&request.password).map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub fn lock(state: State<'_, RuntimeState>) -> Result<AuthStatus, CommandError> {
    state.lock().map_err(CommandError::from)
}

#[tauri::command]
pub fn list_attachments(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<Vec<VaultAttachment>, CommandError> {
    state
        .with_services(|services| services.attachments.list(&request.id))
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn import_attachment(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<Option<VaultAttachment>, CommandError> {
    validate_id(&request.id).map_err(CommandError::from)?;
    let selected = rfd::AsyncFileDialog::new()
        .set_title("Add attachment")
        .pick_file()
        .await;
    let Some(selected) = selected else {
        return Ok(None);
    };
    let source = selected.path().to_owned();
    state
        .with_services(|services| services.attachments.import_from(&request.id, &source))
        .map(Some)
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn import_attachment_path(
    state: State<'_, RuntimeState>,
    request: ImportAttachmentPathRequest,
) -> Result<VaultAttachment, CommandError> {
    validate_id(&request.id).map_err(CommandError::from)?;
    let service = state
        .with_services(|services| Ok(services.attachments.clone()))
        .map_err(CommandError::from)?;
    tokio::task::spawn_blocking(move || service.import_from(&request.id, &request.path))
        .await
        .map_err(|_| {
            CommandError::from(crate::error::AppError::Storage(std::io::Error::other(
                "attachment import task failed",
            )))
        })?
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn export_attachment(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<bool, CommandError> {
    validate_id(&request.id).map_err(CommandError::from)?;
    let filename = state
        .with_services(|services| services.attachments.export_filename(&request.id))
        .map_err(CommandError::from)?;
    let selected = rfd::AsyncFileDialog::new()
        .set_title("Save attachment copy")
        .set_file_name(filename)
        .save_file()
        .await;
    let Some(selected) = selected else {
        return Ok(false);
    };
    let destination = selected.path().to_owned();
    state
        .with_services(|services| services.attachments.export_to(&request.id, &destination))
        .map(|()| true)
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn preview_attachment(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<Response, CommandError> {
    validate_id(&request.id).map_err(CommandError::from)?;
    let service = state
        .with_services(|services| Ok(services.attachments.clone()))
        .map_err(CommandError::from)?;
    tokio::task::spawn_blocking(move || service.preview_image(&request.id))
        .await
        .map_err(|_| CommandError::from(crate::error::AppError::AttachmentPreviewUnavailable))?
        .map(Response::new)
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn open_attachment(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<(), CommandError> {
    validate_id(&request.id).map_err(CommandError::from)?;
    let service = state
        .with_services(|services| Ok(services.attachments.clone()))
        .map_err(CommandError::from)?;
    tokio::task::spawn_blocking(move || service.open(&request.id))
        .await
        .map_err(|_| CommandError::from(crate::error::AppError::AttachmentOpenFailed))?
        .map_err(CommandError::from)
}

#[tauri::command]
pub fn delete_attachment(
    state: State<'_, RuntimeState>,
    request: ItemRequest,
) -> Result<(), CommandError> {
    state
        .with_services(|services| services.attachments.delete(&request.id))
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn export_backup(
    state: State<'_, RuntimeState>,
    mut request: PasswordRequest,
) -> Result<Option<BackupSummary>, CommandError> {
    validate_password(&request.password).map_err(CommandError::from)?;
    let selected = rfd::AsyncFileDialog::new()
        .set_title("Export encrypted VaultNote backup")
        .add_filter("VaultNote backup", &["vnb"])
        .set_file_name("VaultNote.vnb")
        .save_file()
        .await;
    let Some(selected) = selected else {
        request.password.zeroize();
        return Ok(None);
    };
    let destination = selected.path().to_owned();
    let result = state
        .with_services(|services| {
            crate::services::now_epoch_millis().and_then(|now| {
                services
                    .backup
                    .export_to(&request.password, now, destination)
            })
        })
        .map(Some)
        .map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub async fn restore_backup(
    state: State<'_, RuntimeState>,
    mut request: PasswordRequest,
) -> Result<Option<RestoreSummary>, CommandError> {
    validate_password(&request.password).map_err(CommandError::from)?;
    let selected = rfd::AsyncFileDialog::new()
        .set_title("Restore encrypted VaultNote backup")
        .add_filter("VaultNote backup", &["vnb"])
        .pick_file()
        .await;
    let Some(selected) = selected else {
        request.password.zeroize();
        return Ok(None);
    };
    let source = selected.path().to_owned();
    let result = state
        .with_services(|services| services.backup.restore_from(&request.password, &source))
        .map(Some)
        .map_err(CommandError::from);
    request.password.zeroize();
    result
}

#[tauri::command]
pub async fn export_plaintext_backup(
    state: State<'_, RuntimeState>,
) -> Result<Option<BackupSummary>, CommandError> {
    let selected = rfd::AsyncFileDialog::new()
        .set_title("Export readable VaultNote backup")
        .add_filter("VaultNote backup", &["vnb"])
        .set_file_name("VaultNote-plaintext.vnb")
        .save_file()
        .await;
    let Some(selected) = selected else {
        return Ok(None);
    };
    let destination = selected.path().to_owned();
    state
        .with_services(|services| {
            crate::services::now_epoch_millis()
                .and_then(|now| services.backup.export_plaintext_to(now, destination))
        })
        .map(Some)
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn restore_plaintext_backup(
    state: State<'_, RuntimeState>,
) -> Result<Option<RestoreSummary>, CommandError> {
    let selected = rfd::AsyncFileDialog::new()
        .set_title("Restore readable VaultNote backup")
        .add_filter("VaultNote backup", &["vnb"])
        .pick_file()
        .await;
    let Some(selected) = selected else {
        return Ok(None);
    };
    let source = selected.path().to_owned();
    state
        .with_services(|services| services.backup.restore_auto(None, &source))
        .map(Some)
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn inspect_backup_path(
    state: State<'_, RuntimeState>,
    request: BackupPathRequest,
) -> Result<crate::backup::BackupInspection, CommandError> {
    let service = state
        .with_services(|services| Ok(services.backup.clone()))
        .map_err(CommandError::from)?;
    tokio::task::spawn_blocking(move || service.inspect(&request.path))
        .await
        .map_err(|_| CommandError::from(crate::error::AppError::InvalidBackup))?
        .map_err(CommandError::from)
}

#[tauri::command]
pub async fn restore_backup_path(
    state: State<'_, RuntimeState>,
    mut request: RestoreBackupPathRequest,
) -> Result<RestoreSummary, CommandError> {
    let service = state
        .with_services(|services| Ok(services.backup.clone()))
        .map_err(CommandError::from)?;
    let source = request.path.clone();
    let password = request.password.clone().map(zeroize::Zeroizing::new);
    let plaintext_confirmed = request.plaintext_confirmed;
    request.password.zeroize();
    tokio::task::spawn_blocking(move || {
        let inspection = service.inspect(&source)?;
        if inspection.protection == crate::backup::BackupProtection::Plaintext
            && !plaintext_confirmed
        {
            return Err(crate::error::AppError::InvalidInput {
                field: "backup",
                reason: "plaintext backup confirmation is required".to_owned(),
            });
        }
        service.restore_auto(password.as_deref().map(String::as_str), &source)
    })
    .await
    .map_err(|_| CommandError::from(crate::error::AppError::InvalidBackup))?
    .map_err(CommandError::from)
}

async fn pair_with_embedded_relay(
    state: &RuntimeState,
    mut started: EmbeddedRelayStart,
    password: &str,
) -> Result<EmbeddedRelayPairingDetails, crate::error::AppError> {
    let vault_id = started
        .status
        .vault_id
        .as_deref()
        .ok_or(crate::error::AppError::EmbeddedRelayUnavailable)?;
    let certificate_sha256 = started
        .status
        .certificate_sha256
        .as_deref()
        .ok_or(crate::error::AppError::EmbeddedRelayUnavailable)?;
    let port = started
        .status
        .port
        .ok_or(crate::error::AppError::EmbeddedRelayUnavailable)?;
    let sync = state.with_services(|services| Ok(services.sync.clone()))?;
    let token = match started.authentication_token.take() {
        Some(token) => token,
        None => token_for_embedded_relay(&sync, &started.status, password)?,
    };
    sync.pair(PairRelayParameters {
        host_address: "127.0.0.1".to_owned(),
        port,
        certificate_sha256: certificate_sha256.to_owned(),
        authentication_token: token.to_string(),
        sync_password: password.to_owned(),
        expected_vault_id: Some(vault_id.to_owned()),
        fingerprint_confirmed: true,
    })
    .await?;
    Ok(EmbeddedRelayPairingDetails {
        status: started.status,
        authentication_token: token.to_string(),
    })
}

fn token_for_embedded_relay(
    sync: &crate::sync_engine::LanSyncService,
    status: &EmbeddedRelayStatus,
    password: &str,
) -> Result<zeroize::Zeroizing<String>, crate::error::AppError> {
    let vault_id = status
        .vault_id
        .as_deref()
        .ok_or(crate::error::AppError::EmbeddedRelayUnavailable)?;
    let certificate_sha256 = status
        .certificate_sha256
        .as_deref()
        .ok_or(crate::error::AppError::EmbeddedRelayUnavailable)?;
    match sync.authentication_token_for(vault_id, certificate_sha256) {
        Ok(token) => Ok(token),
        Err(crate::error::AppError::SyncLocked) if !password.is_empty() => {
            sync.unlock_sync(password)?;
            sync.authentication_token_for(vault_id, certificate_sha256)
        }
        Err(error) => Err(error),
    }
}

fn validate_sync_password(password: &str) -> Result<(), crate::error::AppError> {
    if (8..=1024).contains(&password.chars().count()) {
        Ok(())
    } else {
        Err(crate::error::AppError::InvalidInput {
            field: "sync_password",
            reason: "sync password must contain 8 to 1024 characters".to_owned(),
        })
    }
}
