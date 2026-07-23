mod backup;
mod commands;
mod crypto;
mod database;
mod error;
mod models;
mod repository;
mod runtime;
mod services;
mod storage;
mod validation;
mod vault_key;

use runtime::RuntimeState;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let database_path = storage::prepare_database_path(app.handle())?;
            app.manage(RuntimeState::new(database_path)?);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_items,
            commands::get_note,
            commands::create_note,
            commands::save_note,
            commands::set_pinned,
            commands::set_favorite,
            commands::set_archived,
            commands::move_to_trash,
            commands::restore,
            commands::search_notes,
            commands::sync_queue_status,
            commands::run_fake_sync,
            commands::auth_status,
            commands::initialize_vault,
            commands::initialize_unencrypted_vault,
            commands::unlock,
            commands::lock,
            commands::list_attachments,
            commands::import_attachment,
            commands::export_attachment,
            commands::delete_attachment,
            commands::export_backup,
            commands::restore_backup,
        ])
        .run(tauri::generate_context!())
        .unwrap_or_else(|_| {
            eprintln!("VaultNote failed to start.");
            std::process::exit(1);
        });
}
