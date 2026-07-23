use serde::Deserialize;
use tauri::State;
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::{
    error::CommandError,
    models::{
        AuthStatus, BackupSummary, RestoreSummary, SearchResult, SyncQueueStatus, SyncReport,
        VaultAttachment, VaultItemSummary, VaultNote,
    },
    runtime::RuntimeState,
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
pub fn run_fake_sync(state: State<'_, RuntimeState>) -> Result<SyncReport, CommandError> {
    state
        .with_services(|services| services.sync.run_once())
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
        .add_filter(
            "Supported files",
            &[
                "txt", "md", "pdf", "png", "jpg", "jpeg", "gif", "webp", "json", "csv", "docx",
                "xlsx", "pptx",
            ],
        )
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
