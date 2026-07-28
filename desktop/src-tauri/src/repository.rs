use std::collections::HashMap;

use chrono::{DateTime, Days, LocalResult, Months, NaiveDateTime, TimeZone, Utc};
use chrono_tz::Tz;
use rusqlite::{Connection, OptionalExtension, Row, Transaction, params};
use uuid::Uuid;

use crate::{
    database::Database,
    error::AppError,
    models::{
        AgendaEntry, AttachmentRecord, DatedEntry, DatedEntryAlert, DatedEntryDraft,
        PortableAttachment, PortableDatedEntry, PortableItem, PortableItemTag, PortableSnapshot,
        PortableTag, ScheduledAlert, SearchResult, SyncQueueStatus, VaultAttachment,
        VaultItemSummary, VaultNote, VaultSection,
    },
};

pub trait VaultRepository: Send + Sync {
    fn list_items(
        &self,
        section: VaultSection,
        limit: usize,
    ) -> Result<Vec<VaultItemSummary>, AppError>;
    fn get_note(&self, id: &str) -> Result<VaultNote, AppError>;
    fn create_note(&self, title: &str, body: &str, now: i64) -> Result<String, AppError>;
    fn save_note(&self, id: &str, title: &str, body: &str, now: i64) -> Result<bool, AppError>;
    fn save_structured_note(
        &self,
        id: &str,
        title: &str,
        body: &str,
        body_document_json: &str,
        now: i64,
    ) -> Result<bool, AppError>;
    fn save_dated_entry(
        &self,
        item_id: &str,
        draft: &DatedEntryDraft,
        now: i64,
    ) -> Result<String, AppError>;
    fn delete_dated_entry(&self, entry_id: &str, now: i64) -> Result<(), AppError>;
    fn complete_dated_entry(&self, entry_id: &str, now: i64) -> Result<(), AppError>;
    fn snooze_dated_entry(&self, entry_id: &str, until_epoch_millis: i64) -> Result<(), AppError>;
    fn list_agenda(
        &self,
        include_completed: bool,
        limit: usize,
    ) -> Result<Vec<AgendaEntry>, AppError>;
    fn get_dated_entry(&self, entry_id: &str) -> Result<AgendaEntry, AppError>;
    fn scheduled_alerts(&self, now: i64, limit: usize) -> Result<Vec<ScheduledAlert>, AppError>;
    fn set_pinned(&self, id: &str, value: bool, now: i64) -> Result<bool, AppError>;
    fn set_favorite(&self, id: &str, value: bool, now: i64) -> Result<bool, AppError>;
    fn set_archived(&self, id: &str, value: bool, now: i64) -> Result<bool, AppError>;
    fn move_to_trash(&self, id: &str, now: i64) -> Result<bool, AppError>;
    fn restore(&self, id: &str, now: i64) -> Result<bool, AppError>;
    fn search(&self, expression: &str, limit: usize) -> Result<Vec<SearchResult>, AppError>;
    fn sync_queue_status(&self) -> Result<SyncQueueStatus, AppError>;
    fn list_attachments(&self, parent_item_id: &str) -> Result<Vec<VaultAttachment>, AppError>;
    fn add_attachment(
        &self,
        record: &AttachmentRecord,
        now: i64,
    ) -> Result<VaultAttachment, AppError>;
    fn attachment_record(&self, id: &str) -> Result<AttachmentRecord, AppError>;
    fn delete_attachment(&self, id: &str, now: i64) -> Result<AttachmentRecord, AppError>;
    fn portable_snapshot(&self) -> Result<PortableSnapshot, AppError>;
    fn restore_portable_snapshot(
        &self,
        snapshot: &PortableSnapshot,
        now: i64,
    ) -> Result<(), AppError>;
}

#[derive(Clone)]
pub struct SqliteVaultRepository {
    database: Database,
}

impl SqliteVaultRepository {
    pub fn new(database: Database) -> Self {
        Self { database }
    }
}

impl VaultRepository for SqliteVaultRepository {
    fn list_items(
        &self,
        section: VaultSection,
        limit: usize,
    ) -> Result<Vec<VaultItemSummary>, AppError> {
        self.database.with_connection(|connection| {
            let sql = match section {
                VaultSection::Active => {
                    r#"
                    SELECT id, type, color, title, substr(body, 1, 180), is_pinned,
                           is_favorite, is_archived, created_at, updated_at, sync_status, deleted_at,
                           (
                               SELECT MIN(d.occurrence_at) FROM dated_entries d
                               WHERE d.item_id = vault_items.id AND d.completed_at IS NULL
                                 AND d.occurrence_at >= CAST(strftime('%s', 'now') AS INTEGER) * 1000
                           ),
                           EXISTS(
                               SELECT 1 FROM dated_entries d
                               WHERE d.item_id = vault_items.id AND d.completed_at IS NULL
                                 AND d.entry_type = 'DEADLINE'
                                 AND d.occurrence_at < CAST(strftime('%s', 'now') AS INTEGER) * 1000
                           )
                    FROM vault_items
                    WHERE type = 'NOTE' AND deleted_at IS NULL AND is_archived = 0
                    ORDER BY is_pinned DESC, updated_at DESC, id ASC
                    LIMIT ?1
                    "#
                }
                VaultSection::Archived => {
                    r#"
                    SELECT id, type, color, title, substr(body, 1, 180), is_pinned,
                           is_favorite, is_archived, created_at, updated_at, sync_status, deleted_at,
                           (
                               SELECT MIN(d.occurrence_at) FROM dated_entries d
                               WHERE d.item_id = vault_items.id AND d.completed_at IS NULL
                                 AND d.occurrence_at >= CAST(strftime('%s', 'now') AS INTEGER) * 1000
                           ),
                           EXISTS(
                               SELECT 1 FROM dated_entries d
                               WHERE d.item_id = vault_items.id AND d.completed_at IS NULL
                                 AND d.entry_type = 'DEADLINE'
                                 AND d.occurrence_at < CAST(strftime('%s', 'now') AS INTEGER) * 1000
                           )
                    FROM vault_items
                    WHERE type = 'NOTE' AND deleted_at IS NULL AND is_archived = 1
                    ORDER BY updated_at DESC, id ASC
                    LIMIT ?1
                    "#
                }
                VaultSection::Trash => {
                    r#"
                    SELECT id, type, color, title, substr(body, 1, 180), is_pinned,
                           is_favorite, is_archived, created_at, updated_at, sync_status, deleted_at,
                           NULL, 0
                    FROM vault_items
                    WHERE type = 'NOTE' AND deleted_at IS NOT NULL
                    ORDER BY deleted_at DESC, id ASC
                    LIMIT ?1
                    "#
                }
            };
            let mut statement = connection.prepare_cached(sql)?;
            let rows = statement.query_map([limit as i64], map_summary)?;
            rows.collect::<Result<Vec<_>, _>>().map_err(AppError::from)
        })
    }

    fn get_note(&self, id: &str) -> Result<VaultNote, AppError> {
        self.database.with_connection(|connection| {
            let mut note = connection
                .query_row(
                    r#"
                    SELECT id, title, body, color, is_pinned, is_favorite, is_archived,
                           created_at, updated_at, local_revision, remote_revision,
                           last_synced_revision, sync_status, deleted_at, body_document_json
                    FROM vault_items
                    WHERE id = ?1 AND type = 'NOTE'
                    LIMIT 1
                    "#,
                    [id],
                    map_note,
                )
                .optional()?
                .ok_or(AppError::NotFound)?;
            note.dated_entries = load_dated_entries(connection, id)?;
            Ok(note)
        })
    }

    fn create_note(&self, title: &str, body: &str, now: i64) -> Result<String, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let id = Uuid::new_v4().hyphenated().to_string();
            transaction.execute(
                r#"
                INSERT INTO vault_items (
                    id, type, color, title, body, ocr_text, is_pinned, is_favorite,
                    is_archived, created_at, updated_at, local_revision, remote_revision,
                    last_synced_revision, server_version_token, sync_status, deleted_at,
                    conflict_origin_id
                ) VALUES (?1, 'NOTE', 'DEFAULT', ?2, ?3, '', 0, 0, 0, ?4, ?4, 1,
                          NULL, NULL, NULL, 'PENDING', NULL, NULL)
                "#,
                params![id, title, body, now],
            )?;
            refresh_fts(&transaction, &id, title, body)?;
            enqueue_item_operation(&transaction, &id, 1, "UPSERT_ITEM", now)?;
            transaction.commit()?;
            Ok(id)
        })
    }

    fn save_note(&self, id: &str, title: &str, body: &str, now: i64) -> Result<bool, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let current = current_item(&transaction, id)?;
            require_editable(&current)?;
            if current.title == title && current.body == body {
                return Ok(false);
            }
            let revision = next_revision(current.local_revision)?;
            let updated_at = now.max(current.updated_at);
            let changed = transaction.execute(
                r#"
                UPDATE vault_items
                SET title = ?1, body = ?2, updated_at = ?3, local_revision = ?4,
                    sync_status = 'PENDING', body_document_json = NULL
                WHERE id = ?5 AND type = 'NOTE'
                "#,
                params![title, body, updated_at, revision, id],
            )?;
            ensure_single_change(changed)?;
            refresh_fts(&transaction, id, title, body)?;
            enqueue_item_operation(&transaction, id, revision, "UPSERT_ITEM", updated_at)?;
            transaction.commit()?;
            Ok(true)
        })
    }

    fn save_structured_note(
        &self,
        id: &str,
        title: &str,
        body: &str,
        body_document_json: &str,
        now: i64,
    ) -> Result<bool, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let current = current_item(&transaction, id)?;
            require_editable(&current)?;
            let existing_document: Option<String> = transaction.query_row(
                "SELECT body_document_json FROM vault_items WHERE id = ?1",
                [id],
                |row| row.get(0),
            )?;
            if current.title == title
                && current.body == body
                && existing_document.as_deref() == Some(body_document_json)
            {
                return Ok(false);
            }
            let revision = next_revision(current.local_revision)?;
            let updated_at = now.max(current.updated_at);
            ensure_single_change(transaction.execute(
                r#"
                UPDATE vault_items
                SET title = ?1, body = ?2, body_document_json = ?3, updated_at = ?4,
                    local_revision = ?5, sync_status = 'PENDING'
                WHERE id = ?6 AND type = 'NOTE'
                "#,
                params![title, body, body_document_json, updated_at, revision, id],
            )?)?;
            refresh_fts(&transaction, id, title, body)?;
            enqueue_item_operation(&transaction, id, revision, "UPSERT_ITEM", updated_at)?;
            transaction.commit()?;
            Ok(true)
        })
    }

    fn save_dated_entry(
        &self,
        item_id: &str,
        draft: &DatedEntryDraft,
        now: i64,
    ) -> Result<String, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let current = current_item(&transaction, item_id)?;
            require_editable(&current)?;
            let entry_id = if let Some(id) = &draft.id {
                let owner: Option<String> = transaction
                    .query_row(
                        "SELECT item_id FROM dated_entries WHERE id = ?1",
                        [id],
                        |row| row.get(0),
                    )
                    .optional()?;
                if owner.as_deref() != Some(item_id) {
                    return Err(AppError::NotFound);
                }
                id.clone()
            } else {
                Uuid::new_v4().hyphenated().to_string()
            };
            if draft.id.is_some() {
                ensure_single_change(transaction.execute(
                    r#"
                    UPDATE dated_entries
                    SET entry_type = ?1, label = ?2, occurrence_at = ?3, is_all_day = ?4,
                        time_zone_id = ?5, recurrence_unit = ?6, recurrence_interval = ?7,
                        completed_at = NULL, updated_at = ?8
                    WHERE id = ?9
                    "#,
                    params![
                        draft.entry_type,
                        draft.label.trim(),
                        draft.occurrence_at_epoch_millis,
                        i64::from(draft.is_all_day),
                        draft.time_zone_id,
                        draft.recurrence_unit,
                        draft.recurrence_interval,
                        now,
                        entry_id,
                    ],
                )?)?;
                transaction.execute(
                    "DELETE FROM dated_entry_alerts WHERE entry_id = ?1",
                    [&entry_id],
                )?;
            } else {
                transaction.execute(
                    r#"
                    INSERT INTO dated_entries (
                        id, item_id, entry_type, label, occurrence_at, is_all_day,
                        time_zone_id, recurrence_unit, recurrence_interval, completed_at,
                        created_at, updated_at
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, NULL, ?10, ?10)
                    "#,
                    params![
                        entry_id,
                        item_id,
                        draft.entry_type,
                        draft.label.trim(),
                        draft.occurrence_at_epoch_millis,
                        i64::from(draft.is_all_day),
                        draft.time_zone_id,
                        draft.recurrence_unit,
                        draft.recurrence_interval,
                        now,
                    ],
                )?;
            }
            for lead in &draft.alert_lead_times_minutes {
                transaction.execute(
                    r#"
                    INSERT INTO dated_entry_alerts (
                        id, entry_id, lead_time_minutes, snoozed_until, created_at
                    ) VALUES (?1, ?2, ?3, NULL, ?4)
                    "#,
                    params![Uuid::new_v4().hyphenated().to_string(), entry_id, lead, now],
                )?;
            }
            advance_item_for_related_change(&transaction, item_id, &current, now)?;
            transaction.commit()?;
            Ok(entry_id)
        })
    }

    fn delete_dated_entry(&self, entry_id: &str, now: i64) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let item_id: String = transaction
                .query_row(
                    "SELECT item_id FROM dated_entries WHERE id = ?1",
                    [entry_id],
                    |row| row.get(0),
                )
                .optional()?
                .ok_or(AppError::NotFound)?;
            let current = current_item(&transaction, &item_id)?;
            require_editable(&current)?;
            ensure_single_change(
                transaction.execute("DELETE FROM dated_entries WHERE id = ?1", [entry_id])?,
            )?;
            advance_item_for_related_change(&transaction, &item_id, &current, now)?;
            transaction.commit()?;
            Ok(())
        })
    }

    fn complete_dated_entry(&self, entry_id: &str, now: i64) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let entry = load_dated_entry(&transaction, entry_id)?.ok_or(AppError::NotFound)?;
            let current = current_item(&transaction, &entry.item_id)?;
            require_editable(&current)?;
            if entry.entry_type == "IMPORTANT_DATE" || entry.completed_at_epoch_millis.is_some() {
                return Ok(());
            }
            if let (Some(unit), Some(interval)) =
                (&entry.recurrence_unit, entry.recurrence_interval)
            {
                let next = next_occurrence(
                    entry.occurrence_at_epoch_millis,
                    &entry.time_zone_id,
                    unit,
                    interval,
                    now,
                )?;
                ensure_single_change(transaction.execute(
                    r#"
                    UPDATE dated_entries
                    SET occurrence_at = ?1, completed_at = NULL, updated_at = ?2
                    WHERE id = ?3
                    "#,
                    params![next, now, entry_id],
                )?)?;
            } else {
                ensure_single_change(transaction.execute(
                    "UPDATE dated_entries SET completed_at = ?1, updated_at = ?1 WHERE id = ?2",
                    params![now, entry_id],
                )?)?;
            }
            transaction.execute(
                "UPDATE dated_entry_alerts SET snoozed_until = NULL WHERE entry_id = ?1",
                [entry_id],
            )?;
            advance_item_for_related_change(&transaction, &entry.item_id, &current, now)?;
            transaction.commit()?;
            Ok(())
        })
    }

    fn snooze_dated_entry(&self, entry_id: &str, until_epoch_millis: i64) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            let exists: bool = connection.query_row(
                "SELECT EXISTS(SELECT 1 FROM dated_entries WHERE id = ?1 AND completed_at IS NULL)",
                [entry_id],
                |row| row.get(0),
            )?;
            if !exists {
                return Err(AppError::NotFound);
            }
            connection.execute(
                "UPDATE dated_entry_alerts SET snoozed_until = ?1 WHERE entry_id = ?2",
                params![until_epoch_millis, entry_id],
            )?;
            Ok(())
        })
    }

    fn list_agenda(
        &self,
        include_completed: bool,
        limit: usize,
    ) -> Result<Vec<AgendaEntry>, AppError> {
        self.database
            .with_connection(|connection| load_agenda(connection, include_completed, limit))
    }

    fn get_dated_entry(&self, entry_id: &str) -> Result<AgendaEntry, AppError> {
        self.database.with_connection(|connection| {
            load_agenda_entry(connection, entry_id)?.ok_or(AppError::NotFound)
        })
    }

    fn scheduled_alerts(&self, now: i64, limit: usize) -> Result<Vec<ScheduledAlert>, AppError> {
        self.database.with_connection(|connection| {
            let mut statement = connection.prepare_cached(
                r#"
                SELECT a.id, d.id, d.item_id,
                       CASE
                           WHEN a.snoozed_until IS NOT NULL AND a.snoozed_until > ?1
                               THEN a.snoozed_until
                           ELSE d.occurrence_at - a.lead_time_minutes * 60000
                       END
                FROM dated_entry_alerts a
                INNER JOIN dated_entries d ON d.id = a.entry_id
                INNER JOIN vault_items v ON v.id = d.item_id
                WHERE d.completed_at IS NULL AND v.deleted_at IS NULL
                  AND (
                      CASE
                          WHEN a.snoozed_until IS NOT NULL AND a.snoozed_until > ?1
                              THEN a.snoozed_until
                          ELSE d.occurrence_at - a.lead_time_minutes * 60000
                      END
                  ) > ?1
                ORDER BY 4 ASC, a.id ASC
                LIMIT ?2
                "#,
            )?;
            statement
                .query_map(params![now, limit as i64], |row| {
                    let alert_id: String = row.get(0)?;
                    Ok(ScheduledAlert {
                        notification_id: notification_id(&alert_id),
                        alert_id,
                        entry_id: row.get(1)?,
                        item_id: row.get(2)?,
                        trigger_at_epoch_millis: row.get(3)?,
                    })
                })?
                .collect::<Result<Vec<_>, _>>()
                .map_err(AppError::from)
        })
    }

    fn set_pinned(&self, id: &str, value: bool, now: i64) -> Result<bool, AppError> {
        update_flag(&self.database, id, Flag::Pinned, value, now)
    }

    fn set_favorite(&self, id: &str, value: bool, now: i64) -> Result<bool, AppError> {
        update_flag(&self.database, id, Flag::Favorite, value, now)
    }

    fn set_archived(&self, id: &str, value: bool, now: i64) -> Result<bool, AppError> {
        update_flag(&self.database, id, Flag::Archived, value, now)
    }

    fn move_to_trash(&self, id: &str, now: i64) -> Result<bool, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let current = current_item(&transaction, id)?;
            if current.deleted_at.is_some() {
                return Ok(false);
            }
            let revision = next_revision(current.local_revision)?;
            let updated_at = now.max(current.updated_at);
            ensure_single_change(transaction.execute(
                r#"
                UPDATE vault_items
                SET deleted_at = ?1, updated_at = ?1, local_revision = ?2,
                    sync_status = 'PENDING'
                WHERE id = ?3 AND type = 'NOTE'
                "#,
                params![updated_at, revision, id],
            )?)?;
            enqueue_item_operation(&transaction, id, revision, "DELETE_ITEM", updated_at)?;
            transaction.commit()?;
            Ok(true)
        })
    }

    fn restore(&self, id: &str, now: i64) -> Result<bool, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let current = current_item(&transaction, id)?;
            if current.deleted_at.is_none() {
                return Ok(false);
            }
            let revision = next_revision(current.local_revision)?;
            let updated_at = now.max(current.updated_at);
            ensure_single_change(transaction.execute(
                r#"
                UPDATE vault_items
                SET deleted_at = NULL, updated_at = ?1, local_revision = ?2,
                    sync_status = 'PENDING'
                WHERE id = ?3 AND type = 'NOTE'
                "#,
                params![updated_at, revision, id],
            )?)?;
            enqueue_item_operation(&transaction, id, revision, "UPSERT_ITEM", updated_at)?;
            transaction.commit()?;
            Ok(true)
        })
    }

    fn search(&self, expression: &str, limit: usize) -> Result<Vec<SearchResult>, AppError> {
        self.database.with_connection(|connection| {
            let mut statement = connection.prepare_cached(
                r#"
                SELECT v.id, v.type, v.color, v.title, substr(v.body, 1, 180),
                       v.is_pinned, v.is_favorite, v.is_archived, v.created_at,
                       v.updated_at, v.sync_status, v.deleted_at,
                       (
                           SELECT MIN(d.occurrence_at) FROM dated_entries d
                           WHERE d.item_id = v.id AND d.completed_at IS NULL
                             AND d.occurrence_at >= CAST(strftime('%s', 'now') AS INTEGER) * 1000
                       ),
                       EXISTS(
                           SELECT 1 FROM dated_entries d
                           WHERE d.item_id = v.id AND d.completed_at IS NULL
                             AND d.entry_type = 'DEADLINE'
                             AND d.occurrence_at < CAST(strftime('%s', 'now') AS INTEGER) * 1000
                       ),
                       snippet(vault_items_fts, 2, '', '', '…', 24)
                FROM vault_items_fts
                INNER JOIN vault_items v ON v.id = vault_items_fts.item_id
                WHERE vault_items_fts MATCH ?1 AND v.type = 'NOTE' AND v.deleted_at IS NULL
                ORDER BY bm25(vault_items_fts), v.is_pinned DESC, v.updated_at DESC, v.id ASC
                LIMIT ?2
                "#,
            )?;
            let rows = statement.query_map(params![expression, limit as i64], |row| {
                Ok(SearchResult {
                    item: map_summary(row)?,
                    snippet: row.get(14)?,
                })
            })?;
            rows.collect::<Result<Vec<_>, _>>().map_err(AppError::from)
        })
    }

    fn sync_queue_status(&self) -> Result<SyncQueueStatus, AppError> {
        self.database.with_connection(|connection| {
            connection
                .query_row(
                    r#"
                    SELECT
                        COALESCE(SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN state = 'RUNNING' THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN state = 'RETRY_WAIT' THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN state = 'FAILED_PERMANENT' THEN 1 ELSE 0 END), 0)
                    FROM sync_operations
                    WHERE state != 'COMPLETED'
                    "#,
                    [],
                    |row| {
                        Ok(SyncQueueStatus {
                            pending_count: row.get(0)?,
                            running_count: row.get(1)?,
                            retry_count: row.get(2)?,
                            failed_count: row.get(3)?,
                        })
                    },
                )
                .map_err(AppError::from)
        })
    }

    fn list_attachments(&self, parent_item_id: &str) -> Result<Vec<VaultAttachment>, AppError> {
        self.database.with_connection(|connection| {
            let mut statement = connection.prepare_cached(
                r#"
                SELECT id, parent_item_id, display_name, mime_type, file_size, sha256, created_at
                FROM attachments
                WHERE parent_item_id = ?1
                ORDER BY created_at ASC, id ASC
                "#,
            )?;
            statement
                .query_map([parent_item_id], map_attachment)?
                .collect::<Result<Vec<_>, _>>()
                .map_err(AppError::from)
        })
    }

    fn add_attachment(
        &self,
        record: &AttachmentRecord,
        now: i64,
    ) -> Result<VaultAttachment, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let current = current_item(&transaction, &record.attachment.parent_item_id)?;
            require_editable(&current)?;
            let revision = next_revision(current.local_revision)?;
            let updated_at = now.max(current.updated_at);
            transaction.execute(
                r#"
                INSERT INTO attachments (
                    id, parent_item_id, display_name, mime_type, file_size, sha256,
                    encrypted_relative_path, encryption_format_version, created_at
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 1, ?8)
                "#,
                params![
                    record.attachment.id,
                    record.attachment.parent_item_id,
                    record.attachment.display_name,
                    record.attachment.mime_type,
                    record.attachment.file_size,
                    record.attachment.sha256,
                    record.encrypted_relative_path,
                    record.attachment.created_at_epoch_millis,
                ],
            )?;
            advance_parent_revision(
                &transaction,
                &record.attachment.parent_item_id,
                revision,
                updated_at,
            )?;
            refresh_fts_from_database(&transaction, &record.attachment.parent_item_id)?;
            enqueue_item_operation(
                &transaction,
                &record.attachment.parent_item_id,
                revision,
                "UPSERT_ITEM",
                updated_at,
            )?;
            transaction.commit()?;
            Ok(record.attachment.clone())
        })
    }

    fn attachment_record(&self, id: &str) -> Result<AttachmentRecord, AppError> {
        self.database.with_connection(|connection| {
            connection
                .query_row(
                    r#"
                    SELECT id, parent_item_id, display_name, mime_type, file_size, sha256,
                           created_at, encrypted_relative_path
                    FROM attachments WHERE id = ?1 LIMIT 1
                    "#,
                    [id],
                    map_attachment_record,
                )
                .optional()?
                .ok_or(AppError::NotFound)
        })
    }

    fn delete_attachment(&self, id: &str, now: i64) -> Result<AttachmentRecord, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let (record, remote_path) = transaction
                .query_row(
                    r#"
                    SELECT id, parent_item_id, display_name, mime_type, file_size, sha256,
                           created_at, encrypted_relative_path, remote_path
                    FROM attachments WHERE id = ?1 LIMIT 1
                    "#,
                    [id],
                    |row| {
                        Ok((
                            map_attachment_record(row)?,
                            row.get::<_, Option<String>>(8)?,
                        ))
                    },
                )
                .optional()?
                .ok_or(AppError::NotFound)?;
            let current = current_item(&transaction, &record.attachment.parent_item_id)?;
            require_editable(&current)?;
            let revision = next_revision(current.local_revision)?;
            let updated_at = now.max(current.updated_at);
            ensure_single_change(
                transaction.execute("DELETE FROM attachments WHERE id = ?1", [id])?,
            )?;
            if let Some(remote_path) =
                remote_path.filter(|path| path == &format!("/v1/attachments/{id}"))
            {
                transaction.execute(
                    r#"
                    INSERT INTO attachment_tombstones (
                        attachment_id, operation_id, remote_path, created_at,
                        attempt_count, next_attempt_at, last_error_code
                    ) VALUES (?1, ?2, ?3, ?4, 0, ?4, NULL)
                    ON CONFLICT(attachment_id) DO NOTHING
                    "#,
                    params![
                        id,
                        format!("delete_attachment_{}", Uuid::new_v4().simple()),
                        remote_path,
                        now,
                    ],
                )?;
            }
            advance_parent_revision(
                &transaction,
                &record.attachment.parent_item_id,
                revision,
                updated_at,
            )?;
            refresh_fts_from_database(&transaction, &record.attachment.parent_item_id)?;
            enqueue_item_operation(
                &transaction,
                &record.attachment.parent_item_id,
                revision,
                "UPSERT_ITEM",
                updated_at,
            )?;
            transaction.commit()?;
            Ok(record)
        })
    }

    fn portable_snapshot(&self) -> Result<PortableSnapshot, AppError> {
        self.database.with_connection(|connection| {
            let items = {
                let mut statement = connection.prepare_cached(
                    r#"
                    SELECT id, type, color, title, body, ocr_text, is_pinned, is_favorite,
                           is_archived, sort_position, created_at, updated_at, local_revision, deleted_at,
                           conflict_origin_id, body_document_json
                    FROM vault_items ORDER BY id
                    "#,
                )?;
                statement
                    .query_map([], |row| {
                        Ok(PortableItem {
                            id: row.get(0)?,
                            item_type: row.get(1)?,
                            color: row.get(2)?,
                            title: row.get(3)?,
                            body: row.get(4)?,
                            ocr_text: row.get(5)?,
                            is_pinned: row.get::<_, i64>(6)? != 0,
                            is_favorite: row.get::<_, i64>(7)? != 0,
                            is_archived: row.get::<_, i64>(8)? != 0,
                            sort_position: row.get(9)?,
                            created_at: row.get(10)?,
                            updated_at: row.get(11)?,
                            local_revision: row.get(12)?,
                            deleted_at: row.get(13)?,
                            conflict_origin_id: row.get(14)?,
                            body_document_json: row.get(15)?,
                        })
                    })?
                    .collect::<Result<Vec<_>, _>>()?
            };
            let tags = {
                let mut statement = connection.prepare_cached(
                    "SELECT id, name, normalized_name, created_at FROM tags ORDER BY id",
                )?;
                statement
                    .query_map([], |row| {
                        Ok(PortableTag {
                            id: row.get(0)?,
                            name: row.get(1)?,
                            normalized_name: row.get(2)?,
                            created_at: row.get(3)?,
                        })
                    })?
                    .collect::<Result<Vec<_>, _>>()?
            };
            let item_tags = {
                let mut statement = connection.prepare_cached(
                    "SELECT item_id, tag_id FROM item_tags ORDER BY item_id, tag_id",
                )?;
                statement
                    .query_map([], |row| {
                        Ok(PortableItemTag {
                            item_id: row.get(0)?,
                            tag_id: row.get(1)?,
                        })
                    })?
                    .collect::<Result<Vec<_>, _>>()?
            };
            let attachments = {
                let mut statement = connection.prepare_cached(
                    r#"
                    SELECT id, parent_item_id, display_name, mime_type, file_size, sha256,
                           created_at, encrypted_relative_path, image_width, image_height,
                           pdf_page_count, ocr_state, ocr_text, ocr_source_checksum,
                           ocr_failure_code, ocr_updated_at
                    FROM attachments ORDER BY id
                    "#,
                )?;
                statement
                    .query_map([], |row| {
                        Ok(PortableAttachment {
                            record: map_attachment_record(row)?,
                            image_width: row.get(8)?,
                            image_height: row.get(9)?,
                            pdf_page_count: row.get(10)?,
                            ocr_state: row.get(11)?,
                            ocr_text: row.get(12)?,
                            ocr_source_checksum: row.get(13)?,
                            ocr_failure_code: row.get(14)?,
                            ocr_updated_at: row.get(15)?,
                        })
                    })?
                    .collect::<Result<Vec<_>, _>>()?
            };
            let dated_entries = load_all_dated_entries(connection)?
                .into_iter()
                .map(|entry| PortableDatedEntry { entry })
                .collect();
            Ok(PortableSnapshot {
                items,
                tags,
                item_tags,
                attachments,
                dated_entries,
            })
        })
    }

    fn restore_portable_snapshot(
        &self,
        snapshot: &PortableSnapshot,
        now: i64,
    ) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            for item in &snapshot.items {
                transaction.execute(
                    r#"
                    INSERT INTO vault_items (
                        id, type, color, title, body, ocr_text, is_pinned, is_favorite,
                        is_archived, created_at, updated_at, local_revision, remote_revision,
                        last_synced_revision, server_version_token, sync_status, deleted_at,
                        conflict_origin_id, body_document_json, sort_position
                    ) VALUES (?1, 'NOTE', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11,
                              NULL, NULL, NULL, 'PENDING', ?12, ?13, ?14, ?15)
                    "#,
                    params![
                        item.id,
                        item.color,
                        item.title,
                        item.body,
                        item.ocr_text,
                        i64::from(item.is_pinned),
                        i64::from(item.is_favorite),
                        i64::from(item.is_archived),
                        item.created_at,
                        item.updated_at,
                        item.local_revision,
                        item.deleted_at,
                        item.conflict_origin_id,
                        item.body_document_json,
                        item.sort_position,
                    ],
                )?;
            }
            let mut restored_tag_ids = HashMap::new();
            for tag in &snapshot.tags {
                let existing_id: Option<String> = transaction
                    .query_row(
                        "SELECT id FROM tags WHERE normalized_name = ?1 LIMIT 1",
                        [&tag.normalized_name],
                        |row| row.get(0),
                    )
                    .optional()?;
                let actual_id = if let Some(existing_id) = existing_id {
                    existing_id
                } else {
                    transaction.execute(
                        "INSERT INTO tags(id, name, normalized_name, created_at) VALUES (?1, ?2, ?3, ?4)",
                        params![tag.id, tag.name, tag.normalized_name, tag.created_at],
                    )?;
                    tag.id.clone()
                };
                restored_tag_ids.insert(tag.id.as_str(), actual_id);
            }
            for reference in &snapshot.item_tags {
                let tag_id = restored_tag_ids
                    .get(reference.tag_id.as_str())
                    .ok_or(AppError::InvalidBackup)?;
                transaction.execute(
                    "INSERT INTO item_tags(item_id, tag_id) VALUES (?1, ?2)",
                    params![reference.item_id, tag_id],
                )?;
            }
            for portable in &snapshot.attachments {
                let record = &portable.record;
                transaction.execute(
                    r#"
                    INSERT INTO attachments (
                        id, parent_item_id, display_name, mime_type, file_size, sha256,
                        encrypted_relative_path, encryption_format_version, created_at,
                        image_width, image_height, pdf_page_count, ocr_state, ocr_text,
                        ocr_source_checksum, ocr_failure_code, ocr_updated_at
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 1, ?8, ?9, ?10, ?11, ?12,
                              ?13, ?14, ?15, ?16)
                    "#,
                    params![
                        record.attachment.id,
                        record.attachment.parent_item_id,
                        record.attachment.display_name,
                        record.attachment.mime_type,
                        record.attachment.file_size,
                        record.attachment.sha256,
                        record.encrypted_relative_path,
                        record.attachment.created_at_epoch_millis,
                        portable.image_width,
                        portable.image_height,
                        portable.pdf_page_count,
                        portable.ocr_state,
                        portable.ocr_text,
                        portable.ocr_source_checksum,
                        portable.ocr_failure_code,
                        portable.ocr_updated_at,
                    ],
                )?;
            }
            for portable in &snapshot.dated_entries {
                let entry = &portable.entry;
                transaction.execute(
                    r#"
                    INSERT INTO dated_entries (
                        id, item_id, entry_type, label, occurrence_at, is_all_day,
                        time_zone_id, recurrence_unit, recurrence_interval, completed_at,
                        created_at, updated_at
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
                    "#,
                    params![
                        entry.id,
                        entry.item_id,
                        entry.entry_type,
                        entry.label,
                        entry.occurrence_at_epoch_millis,
                        i64::from(entry.is_all_day),
                        entry.time_zone_id,
                        entry.recurrence_unit,
                        entry.recurrence_interval,
                        entry.completed_at_epoch_millis,
                        entry.created_at_epoch_millis,
                        entry.updated_at_epoch_millis,
                    ],
                )?;
                for alert in &entry.alerts {
                    transaction.execute(
                        r#"
                        INSERT INTO dated_entry_alerts (
                            id, entry_id, lead_time_minutes, snoozed_until, created_at
                        ) VALUES (?1, ?2, ?3, NULL, ?4)
                        "#,
                        params![
                            alert.id,
                            entry.id,
                            alert.lead_time_minutes,
                            entry.created_at_epoch_millis
                        ],
                    )?;
                }
            }
            for item in &snapshot.items {
                refresh_fts(&transaction, &item.id, &item.title, &item.body)?;
                enqueue_item_operation(
                    &transaction,
                    &item.id,
                    item.local_revision,
                    if item.deleted_at.is_some() {
                        "DELETE_ITEM"
                    } else {
                        "UPSERT_ITEM"
                    },
                    now,
                )?;
            }
            transaction.commit()?;
            Ok(())
        })
    }
}

#[derive(Debug)]
struct CurrentItem {
    title: String,
    body: String,
    is_pinned: bool,
    is_favorite: bool,
    is_archived: bool,
    updated_at: i64,
    local_revision: i64,
    deleted_at: Option<i64>,
}

#[derive(Debug, Clone, Copy)]
enum Flag {
    Pinned,
    Favorite,
    Archived,
}

fn update_flag(
    database: &Database,
    id: &str,
    flag: Flag,
    value: bool,
    now: i64,
) -> Result<bool, AppError> {
    database.with_connection(|connection| {
        let transaction = connection.transaction()?;
        let current = current_item(&transaction, id)?;
        require_editable(&current)?;
        let unchanged = match flag {
            Flag::Pinned => current.is_pinned == value,
            Flag::Favorite => current.is_favorite == value,
            Flag::Archived => current.is_archived == value,
        };
        if unchanged {
            return Ok(false);
        }
        let revision = next_revision(current.local_revision)?;
        let updated_at = now.max(current.updated_at);
        let sql = match flag {
            Flag::Pinned => {
                "UPDATE vault_items SET is_pinned = ?1, updated_at = ?2, local_revision = ?3, \
                 sync_status = 'PENDING' WHERE id = ?4 AND type = 'NOTE'"
            }
            Flag::Favorite => {
                "UPDATE vault_items SET is_favorite = ?1, updated_at = ?2, local_revision = ?3, \
                 sync_status = 'PENDING' WHERE id = ?4 AND type = 'NOTE'"
            }
            Flag::Archived => {
                "UPDATE vault_items SET is_archived = ?1, updated_at = ?2, local_revision = ?3, \
                 sync_status = 'PENDING' WHERE id = ?4 AND type = 'NOTE'"
            }
        };
        ensure_single_change(
            transaction.execute(sql, params![i64::from(value), updated_at, revision, id])?,
        )?;
        enqueue_item_operation(&transaction, id, revision, "UPSERT_ITEM", updated_at)?;
        transaction.commit()?;
        Ok(true)
    })
}

fn current_item(transaction: &Transaction<'_>, id: &str) -> Result<CurrentItem, AppError> {
    transaction
        .query_row(
            r#"
            SELECT title, body, is_pinned, is_favorite, is_archived, updated_at,
                   local_revision, deleted_at
            FROM vault_items
            WHERE id = ?1 AND type = 'NOTE'
            LIMIT 1
            "#,
            [id],
            |row| {
                Ok(CurrentItem {
                    title: row.get(0)?,
                    body: row.get(1)?,
                    is_pinned: row.get::<_, i64>(2)? != 0,
                    is_favorite: row.get::<_, i64>(3)? != 0,
                    is_archived: row.get::<_, i64>(4)? != 0,
                    updated_at: row.get(5)?,
                    local_revision: row.get(6)?,
                    deleted_at: row.get(7)?,
                })
            },
        )
        .optional()?
        .ok_or(AppError::NotFound)
}

fn require_editable(item: &CurrentItem) -> Result<(), AppError> {
    if item.deleted_at.is_some() {
        Err(AppError::InvalidState)
    } else {
        Ok(())
    }
}

fn next_revision(current: i64) -> Result<i64, AppError> {
    current.checked_add(1).ok_or(AppError::InvalidState)
}

fn ensure_single_change(changed: usize) -> Result<(), AppError> {
    if changed == 1 {
        Ok(())
    } else {
        Err(AppError::NotFound)
    }
}

fn refresh_fts(
    transaction: &Transaction<'_>,
    id: &str,
    title: &str,
    body: &str,
) -> Result<(), AppError> {
    transaction.execute("DELETE FROM vault_items_fts WHERE item_id = ?1", [id])?;
    let filenames = attachment_filenames(transaction, id)?;
    transaction.execute(
        "INSERT INTO vault_items_fts(item_id, title, body, attachment_filenames) VALUES (?1, ?2, ?3, ?4)",
        params![id, title, body, filenames],
    )?;
    Ok(())
}

fn refresh_fts_from_database(transaction: &Transaction<'_>, id: &str) -> Result<(), AppError> {
    let (title, body): (String, String) = transaction
        .query_row(
            "SELECT title, body FROM vault_items WHERE id = ?1",
            [id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?
        .ok_or(AppError::NotFound)?;
    refresh_fts(transaction, id, &title, &body)
}

fn attachment_filenames(transaction: &Transaction<'_>, id: &str) -> Result<String, AppError> {
    let mut statement = transaction.prepare_cached(
        "SELECT display_name FROM attachments WHERE parent_item_id = ?1 ORDER BY created_at, id",
    )?;
    let filenames = statement
        .query_map([id], |row| row.get::<_, String>(0))?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(filenames.join("\n"))
}

fn advance_parent_revision(
    transaction: &Transaction<'_>,
    id: &str,
    revision: i64,
    updated_at: i64,
) -> Result<(), AppError> {
    ensure_single_change(transaction.execute(
        r#"
        UPDATE vault_items
        SET updated_at = ?1, local_revision = ?2, sync_status = 'PENDING'
        WHERE id = ?3 AND type = 'NOTE'
        "#,
        params![updated_at, revision, id],
    )?)
}

fn enqueue_item_operation(
    transaction: &Transaction<'_>,
    item_id: &str,
    target_revision: i64,
    operation_type: &str,
    now: i64,
) -> Result<(), AppError> {
    let operation_id = Uuid::new_v4().hyphenated().to_string();
    let dedupe_key = format!("item:{item_id}");
    transaction.execute(
        r#"
        INSERT INTO sync_operations (
            operation_id, dedupe_key, item_id, operation_type, target_revision, state,
            attempt_count, next_attempt_at, lease_token, lease_expires_at, created_at,
            updated_at, last_error_code
        ) VALUES (?1, ?2, ?3, ?4, ?5, 'PENDING', 0, ?6, NULL, NULL, ?6, ?6, NULL)
        ON CONFLICT(dedupe_key) DO UPDATE SET
            operation_id = excluded.operation_id,
            item_id = excluded.item_id,
            operation_type = excluded.operation_type,
            target_revision = excluded.target_revision,
            state = 'PENDING',
            attempt_count = 0,
            next_attempt_at = excluded.next_attempt_at,
            lease_token = NULL,
            lease_expires_at = NULL,
            created_at = excluded.created_at,
            updated_at = excluded.updated_at,
            last_error_code = NULL
        "#,
        params![
            operation_id,
            dedupe_key,
            item_id,
            operation_type,
            target_revision,
            now
        ],
    )?;
    Ok(())
}

fn advance_item_for_related_change(
    transaction: &Transaction<'_>,
    item_id: &str,
    current: &CurrentItem,
    now: i64,
) -> Result<(), AppError> {
    let revision = next_revision(current.local_revision)?;
    let updated_at = now.max(current.updated_at);
    advance_parent_revision(transaction, item_id, revision, updated_at)?;
    enqueue_item_operation(transaction, item_id, revision, "UPSERT_ITEM", updated_at)
}

fn load_dated_entries(connection: &Connection, item_id: &str) -> Result<Vec<DatedEntry>, AppError> {
    let mut statement = connection.prepare_cached(
        r#"
        SELECT id, item_id, entry_type, label, occurrence_at, is_all_day, time_zone_id,
               recurrence_unit, recurrence_interval, completed_at, created_at, updated_at
        FROM dated_entries
        WHERE item_id = ?1
        ORDER BY completed_at IS NOT NULL ASC, occurrence_at ASC, id ASC
        "#,
    )?;
    let mut entries = statement
        .query_map([item_id], map_dated_entry)?
        .collect::<Result<Vec<_>, _>>()?;
    drop(statement);
    for entry in &mut entries {
        entry.alerts = load_dated_entry_alerts(connection, &entry.id)?;
    }
    Ok(entries)
}

fn load_all_dated_entries(connection: &Connection) -> Result<Vec<DatedEntry>, AppError> {
    let mut statement = connection.prepare_cached(
        r#"
        SELECT id, item_id, entry_type, label, occurrence_at, is_all_day, time_zone_id,
               recurrence_unit, recurrence_interval, completed_at, created_at, updated_at
        FROM dated_entries ORDER BY id
        "#,
    )?;
    let mut entries = statement
        .query_map([], map_dated_entry)?
        .collect::<Result<Vec<_>, _>>()?;
    drop(statement);
    for entry in &mut entries {
        entry.alerts = load_dated_entry_alerts(connection, &entry.id)?;
    }
    Ok(entries)
}

fn load_dated_entry(
    connection: &Connection,
    entry_id: &str,
) -> Result<Option<DatedEntry>, AppError> {
    let mut entry = connection
        .query_row(
            r#"
            SELECT id, item_id, entry_type, label, occurrence_at, is_all_day, time_zone_id,
                   recurrence_unit, recurrence_interval, completed_at, created_at, updated_at
            FROM dated_entries WHERE id = ?1 LIMIT 1
            "#,
            [entry_id],
            map_dated_entry,
        )
        .optional()?;
    if let Some(entry) = &mut entry {
        entry.alerts = load_dated_entry_alerts(connection, &entry.id)?;
    }
    Ok(entry)
}

fn load_dated_entry_alerts(
    connection: &Connection,
    entry_id: &str,
) -> Result<Vec<DatedEntryAlert>, AppError> {
    let mut statement = connection.prepare_cached(
        r#"
        SELECT id, lead_time_minutes, snoozed_until
        FROM dated_entry_alerts
        WHERE entry_id = ?1
        ORDER BY lead_time_minutes ASC, id ASC
        "#,
    )?;
    statement
        .query_map([entry_id], |row| {
            Ok(DatedEntryAlert {
                id: row.get(0)?,
                lead_time_minutes: row.get(1)?,
                snoozed_until_epoch_millis: row.get(2)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()
        .map_err(AppError::from)
}

fn load_agenda(
    connection: &Connection,
    include_completed: bool,
    limit: usize,
) -> Result<Vec<AgendaEntry>, AppError> {
    let mut statement = connection.prepare_cached(
        r#"
        SELECT d.id, v.title, v.is_archived
        FROM dated_entries d
        INNER JOIN vault_items v ON v.id = d.item_id
        WHERE v.deleted_at IS NULL AND (?1 = 1 OR d.completed_at IS NULL)
        ORDER BY d.occurrence_at ASC, d.id ASC
        LIMIT ?2
        "#,
    )?;
    let metadata = statement
        .query_map(params![i64::from(include_completed), limit as i64], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)? != 0,
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    drop(statement);
    metadata
        .into_iter()
        .map(|(id, note_title, is_archived)| {
            Ok(AgendaEntry {
                entry: load_dated_entry(connection, &id)?.ok_or(AppError::NotFound)?,
                note_title,
                is_archived,
            })
        })
        .collect()
}

fn load_agenda_entry(
    connection: &Connection,
    entry_id: &str,
) -> Result<Option<AgendaEntry>, AppError> {
    let metadata: Option<(String, bool)> = connection
        .query_row(
            r#"
            SELECT v.title, v.is_archived
            FROM dated_entries d
            INNER JOIN vault_items v ON v.id = d.item_id
            WHERE d.id = ?1 AND v.deleted_at IS NULL
            LIMIT 1
            "#,
            [entry_id],
            |row| Ok((row.get(0)?, row.get::<_, i64>(1)? != 0)),
        )
        .optional()?;
    metadata
        .map(|(note_title, is_archived)| {
            Ok(AgendaEntry {
                entry: load_dated_entry(connection, entry_id)?.ok_or(AppError::NotFound)?,
                note_title,
                is_archived,
            })
        })
        .transpose()
}

fn map_dated_entry(row: &Row<'_>) -> rusqlite::Result<DatedEntry> {
    Ok(DatedEntry {
        id: row.get(0)?,
        item_id: row.get(1)?,
        entry_type: row.get(2)?,
        label: row.get(3)?,
        occurrence_at_epoch_millis: row.get(4)?,
        is_all_day: row.get::<_, i64>(5)? != 0,
        time_zone_id: row.get(6)?,
        recurrence_unit: row.get(7)?,
        recurrence_interval: row.get(8)?,
        completed_at_epoch_millis: row.get(9)?,
        created_at_epoch_millis: row.get(10)?,
        updated_at_epoch_millis: row.get(11)?,
        alerts: Vec::new(),
    })
}

fn notification_id(alert_id: &str) -> i32 {
    Uuid::parse_str(alert_id)
        .map(|id| {
            let bytes = id.as_bytes();
            i32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
        })
        .unwrap_or(0)
}

fn next_occurrence(
    occurrence_at: i64,
    time_zone_id: &str,
    unit: &str,
    interval: i64,
    after: i64,
) -> Result<i64, AppError> {
    let timezone: Tz = time_zone_id.parse().map_err(|_| AppError::InvalidState)?;
    let instant =
        DateTime::<Utc>::from_timestamp_millis(occurrence_at).ok_or(AppError::InvalidState)?;
    let mut local = instant.with_timezone(&timezone);
    for _ in 0..10_000 {
        let naive = local.naive_local();
        let next_naive = match unit {
            "DAY" => naive.checked_add_days(Days::new(interval as u64)),
            "WEEK" => naive.checked_add_days(Days::new(
                interval.checked_mul(7).ok_or(AppError::InvalidState)? as u64,
            )),
            "MONTH" => naive.checked_add_months(Months::new(interval as u32)),
            "YEAR" => naive.checked_add_months(Months::new(
                interval
                    .checked_mul(12)
                    .and_then(|value| u32::try_from(value).ok())
                    .ok_or(AppError::InvalidState)?,
            )),
            _ => return Err(AppError::InvalidState),
        }
        .ok_or(AppError::InvalidState)?;
        local = resolve_local_datetime(timezone, next_naive)?;
        let next = local.timestamp_millis();
        if next > after {
            return Ok(next);
        }
    }
    Err(AppError::InvalidState)
}

fn resolve_local_datetime(
    timezone: Tz,
    mut local: NaiveDateTime,
) -> Result<DateTime<Tz>, AppError> {
    for _ in 0..=180 {
        match timezone.from_local_datetime(&local) {
            LocalResult::Single(value) => return Ok(value),
            LocalResult::Ambiguous(first, _) => return Ok(first),
            LocalResult::None => {
                local = local
                    .checked_add_signed(chrono::Duration::minutes(1))
                    .ok_or(AppError::InvalidState)?;
            }
        }
    }
    Err(AppError::InvalidState)
}

fn map_summary(row: &Row<'_>) -> rusqlite::Result<VaultItemSummary> {
    Ok(VaultItemSummary {
        id: row.get(0)?,
        item_type: row.get(1)?,
        color: row.get(2)?,
        title: row.get(3)?,
        body_preview: row.get(4)?,
        is_pinned: row.get::<_, i64>(5)? != 0,
        is_favorite: row.get::<_, i64>(6)? != 0,
        is_archived: row.get::<_, i64>(7)? != 0,
        created_at_epoch_millis: row.get(8)?,
        updated_at_epoch_millis: row.get(9)?,
        sync_status: row.get(10)?,
        deleted_at_epoch_millis: row.get(11)?,
        next_dated_entry_at_epoch_millis: row.get(12)?,
        has_overdue_entry: row.get::<_, i64>(13)? != 0,
    })
}

fn map_note(row: &Row<'_>) -> rusqlite::Result<VaultNote> {
    let body_document = row
        .get::<_, Option<String>>(14)?
        .map(|json| serde_json::from_str(&json))
        .transpose()
        .map_err(|error| {
            rusqlite::Error::FromSqlConversionFailure(
                14,
                rusqlite::types::Type::Text,
                Box::new(error),
            )
        })?;
    Ok(VaultNote {
        id: row.get(0)?,
        title: row.get(1)?,
        body: row.get(2)?,
        color: row.get(3)?,
        is_pinned: row.get::<_, i64>(4)? != 0,
        is_favorite: row.get::<_, i64>(5)? != 0,
        is_archived: row.get::<_, i64>(6)? != 0,
        created_at_epoch_millis: row.get(7)?,
        updated_at_epoch_millis: row.get(8)?,
        local_revision: row.get(9)?,
        remote_revision: row.get(10)?,
        last_synced_revision: row.get(11)?,
        sync_status: row.get(12)?,
        deleted_at_epoch_millis: row.get(13)?,
        body_document,
        dated_entries: Vec::new(),
    })
}

fn map_attachment(row: &Row<'_>) -> rusqlite::Result<VaultAttachment> {
    Ok(VaultAttachment {
        id: row.get(0)?,
        parent_item_id: row.get(1)?,
        display_name: row.get(2)?,
        mime_type: row.get(3)?,
        file_size: row.get(4)?,
        sha256: row.get(5)?,
        created_at_epoch_millis: row.get(6)?,
    })
}

fn map_attachment_record(row: &Row<'_>) -> rusqlite::Result<AttachmentRecord> {
    Ok(AttachmentRecord {
        attachment: map_attachment(row)?,
        encrypted_relative_path: row.get(7)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{database::Database, validation::compile_search_query};

    fn repository() -> SqliteVaultRepository {
        SqliteVaultRepository::new(Database::open_in_memory().expect("database should open"))
    }

    #[test]
    fn create_save_and_metadata_changes_are_atomic_and_coalesced() {
        let repository = repository();
        let id = repository
            .create_note("", "", 1_000)
            .expect("create should work");
        repository
            .save_note(&id, "Offline plan", "Keep this available", 1_001)
            .expect("save should work");
        repository
            .set_pinned(&id, true, 1_002)
            .expect("pin should work");
        repository
            .set_favorite(&id, true, 1_003)
            .expect("favorite should work");

        let note = repository.get_note(&id).expect("note should exist");
        let queue = repository.sync_queue_status().expect("queue should load");
        assert_eq!(note.title, "Offline plan");
        assert_eq!(note.body, "Keep this available");
        assert!(note.is_pinned);
        assert!(note.is_favorite);
        assert_eq!(note.local_revision, 4);
        assert_eq!(queue.pending_count, 1);

        let changed = repository
            .save_note(&id, "Offline plan", "Keep this available", 1_004)
            .expect("no-op save should work");
        assert!(!changed);
        assert_eq!(
            repository
                .get_note(&id)
                .expect("note should exist")
                .local_revision,
            4
        );
    }

    #[test]
    fn archive_trash_and_restore_preserve_note_content() {
        let repository = repository();
        let id = repository
            .create_note("Recoverable", "Body", 2_000)
            .expect("create should work");
        repository
            .set_archived(&id, true, 2_001)
            .expect("archive should work");
        assert_eq!(
            repository
                .list_items(VaultSection::Archived, 100)
                .unwrap()
                .len(),
            1
        );

        repository
            .move_to_trash(&id, 2_002)
            .expect("trash should work");
        assert_eq!(
            repository
                .list_items(VaultSection::Trash, 100)
                .unwrap()
                .len(),
            1
        );
        repository.restore(&id, 2_003).expect("restore should work");

        let restored = repository.get_note(&id).expect("note should exist");
        assert_eq!(restored.title, "Recoverable");
        assert_eq!(restored.body, "Body");
        assert!(restored.is_archived);
        assert!(restored.deleted_at_epoch_millis.is_none());
    }

    #[test]
    fn fts5_search_updates_with_note_content_and_excludes_trash() {
        let repository = repository();
        let id = repository
            .create_note("Travel plan", "Train to Bangkok", 3_000)
            .expect("create should work");
        let expression = compile_search_query("Bang").unwrap().unwrap();
        let matches = repository
            .search(&expression, 100)
            .expect("search should work");
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].item.id, id);

        repository
            .save_note(&id, "Travel plan", "Ferry to Samui", 3_001)
            .expect("save should work");
        assert!(repository.search(&expression, 100).unwrap().is_empty());
        repository
            .move_to_trash(&id, 3_002)
            .expect("trash should work");
        let updated_expression = compile_search_query("Samui").unwrap().unwrap();
        assert!(
            repository
                .search(&updated_expression, 100)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn structured_checklists_and_recurring_renewal_alerts_round_trip() {
        let repository = repository();
        let item_id = repository
            .create_note("", "", 1_000)
            .expect("create should work");
        let document = r#"{"version":1,"blocks":[{"id":"00000000-0000-0000-0000-000000000201","type":"PARAGRAPH","text":"Domain","checked":false},{"id":"00000000-0000-0000-0000-000000000202","type":"CHECKLIST_ITEM","text":"Confirm payment","checked":true}]}"#;
        repository
            .save_structured_note(
                &item_id,
                "Renewal",
                "Domain\n[x] Confirm payment",
                document,
                1_001,
            )
            .expect("structured save should work");
        let occurrence = 1_700_000_000_000;
        let entry_id = repository
            .save_dated_entry(
                &item_id,
                &DatedEntryDraft {
                    id: None,
                    entry_type: "RENEWAL".to_owned(),
                    label: "Renew domain".to_owned(),
                    occurrence_at_epoch_millis: occurrence,
                    is_all_day: false,
                    time_zone_id: "Asia/Bangkok".to_owned(),
                    recurrence_unit: Some("YEAR".to_owned()),
                    recurrence_interval: Some(1),
                    alert_lead_times_minutes: vec![0, 60, 1_440],
                },
                1_002,
            )
            .expect("dated entry should save");

        let note = repository.get_note(&item_id).expect("note should load");
        assert_eq!(note.body, "Domain\n[x] Confirm payment");
        assert_eq!(
            note.body_document
                .expect("document should decode")
                .blocks
                .len(),
            2
        );
        assert_eq!(note.dated_entries[0].alerts.len(), 3);
        assert_eq!(repository.list_agenda(false, 100).unwrap().len(), 1);
        assert_eq!(
            repository
                .scheduled_alerts(occurrence - 2 * 86_400_000, 100)
                .unwrap()
                .len(),
            3
        );

        repository
            .complete_dated_entry(&entry_id, occurrence + 1)
            .expect("renewal should advance");
        let advanced = repository
            .get_dated_entry(&entry_id)
            .expect("entry should remain active")
            .entry;
        assert!(advanced.occurrence_at_epoch_millis > occurrence);
        assert!(advanced.completed_at_epoch_millis.is_none());
    }

    #[test]
    fn portable_restore_commits_metadata_fts_and_sync_intent_atomically() {
        let repository = repository();
        let item_id = "00000000-0000-0000-0000-000000000101".to_owned();
        let tag_id = "00000000-0000-0000-0000-000000000102".to_owned();
        let attachment_id = "00000000-0000-0000-0000-000000000103".to_owned();
        let snapshot = PortableSnapshot {
            items: vec![PortableItem {
                id: item_id.clone(),
                item_type: "NOTE".to_owned(),
                color: "BLUE".to_owned(),
                title: "Restored".to_owned(),
                body: "Portable content".to_owned(),
                ocr_text: String::new(),
                is_pinned: true,
                is_favorite: false,
                is_archived: false,
                sort_position: 7,
                created_at: 5_000,
                updated_at: 5_001,
                local_revision: 3,
                deleted_at: None,
                conflict_origin_id: None,
                body_document_json: None,
            }],
            tags: vec![PortableTag {
                id: tag_id.clone(),
                name: "Travel".to_owned(),
                normalized_name: "travel".to_owned(),
                created_at: 5_000,
            }],
            item_tags: vec![PortableItemTag {
                item_id: item_id.clone(),
                tag_id,
            }],
            attachments: vec![PortableAttachment {
                record: AttachmentRecord {
                    attachment: VaultAttachment {
                        id: attachment_id.clone(),
                        parent_item_id: item_id.clone(),
                        display_name: "itinerary.pdf".to_owned(),
                        mime_type: "application/pdf".to_owned(),
                        file_size: 8,
                        sha256: "0".repeat(64),
                        created_at_epoch_millis: 5_001,
                    },
                    encrypted_relative_path: format!("{attachment_id}.vne"),
                },
                image_width: None,
                image_height: None,
                pdf_page_count: Some(1),
                ocr_state: "COMPLETE".to_owned(),
                ocr_text: "schedule".to_owned(),
                ocr_source_checksum: Some("0".repeat(64)),
                ocr_failure_code: None,
                ocr_updated_at: Some(5_002),
            }],
            dated_entries: vec![PortableDatedEntry {
                entry: DatedEntry {
                    id: "00000000-0000-0000-0000-000000000104".to_owned(),
                    item_id: item_id.clone(),
                    entry_type: "DEADLINE".to_owned(),
                    label: "Submit itinerary".to_owned(),
                    occurrence_at_epoch_millis: 6_000,
                    is_all_day: false,
                    time_zone_id: "UTC".to_owned(),
                    recurrence_unit: None,
                    recurrence_interval: None,
                    completed_at_epoch_millis: None,
                    created_at_epoch_millis: 5_001,
                    updated_at_epoch_millis: 5_002,
                    alerts: vec![DatedEntryAlert {
                        id: "00000000-0000-0000-0000-000000000105".to_owned(),
                        lead_time_minutes: 60,
                        snoozed_until_epoch_millis: None,
                    }],
                },
            }],
        };

        repository
            .restore_portable_snapshot(&snapshot, 5_003)
            .expect("portable restore should commit");

        let note = repository.get_note(&item_id).expect("note should exist");
        let attachments = repository
            .list_attachments(&item_id)
            .expect("attachment should exist");
        let query = compile_search_query("itinerary").unwrap().unwrap();
        assert_eq!(note.local_revision, 3);
        assert_eq!(attachments.len(), 1);
        assert_eq!(repository.search(&query, 10).unwrap().len(), 1);
        assert_eq!(repository.sync_queue_status().unwrap().pending_count, 1);
        assert_eq!(repository.portable_snapshot().unwrap(), snapshot);
    }
}
