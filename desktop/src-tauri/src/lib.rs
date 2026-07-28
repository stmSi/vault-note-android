mod backup;
mod commands;
mod crypto;
mod database;
mod embedded_relay;
mod error;
mod lan_discovery;
mod models;
mod nearby_pairing;
mod relay_client;
mod repository;
mod runtime;
mod services;
mod storage;
mod sync_credentials;
mod sync_crypto;
mod sync_engine;
mod sync_store;
mod sync_wire;
mod validation;
mod vault_key;

use runtime::RuntimeState;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let database_path = storage::prepare_database_path(app.handle())?;
            let runtime = RuntimeState::new(database_path)?;
            let embedded_relay = runtime.embedded_relay();
            app.manage(runtime);
            tauri::async_runtime::spawn(async move {
                let _ = embedded_relay.start_if_enabled().await;
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_items,
            commands::get_note,
            commands::create_note,
            commands::save_note,
            commands::save_structured_note,
            commands::save_dated_entry,
            commands::delete_dated_entry,
            commands::complete_dated_entry,
            commands::snooze_dated_entry,
            commands::list_agenda,
            commands::scheduled_alerts,
            commands::export_calendar_entry,
            commands::set_pinned,
            commands::set_favorite,
            commands::set_archived,
            commands::move_to_trash,
            commands::restore,
            commands::search_notes,
            commands::sync_queue_status,
            commands::sync_connection_status,
            commands::discover_relays,
            commands::pair_relay,
            commands::unlock_sync,
            commands::disconnect_relay,
            commands::run_sync,
            commands::embedded_relay_status,
            commands::enable_embedded_relay,
            commands::embedded_relay_pairing_details,
            commands::reset_embedded_relay_access,
            commands::pending_nearby_pairings,
            commands::approve_nearby_pairing,
            commands::reject_nearby_pairing,
            commands::auth_status,
            commands::initialize_vault,
            commands::initialize_unencrypted_vault,
            commands::unlock,
            commands::lock,
            commands::list_attachments,
            commands::import_attachment,
            commands::import_attachment_path,
            commands::export_attachment,
            commands::delete_attachment,
            commands::export_backup,
            commands::restore_backup,
            commands::export_plaintext_backup,
            commands::restore_plaintext_backup,
            commands::inspect_backup_path,
            commands::restore_backup_path,
        ])
        .run(tauri::generate_context!())
        .unwrap_or_else(|_| {
            eprintln!("VaultNote failed to start.");
            std::process::exit(1);
        });
}
