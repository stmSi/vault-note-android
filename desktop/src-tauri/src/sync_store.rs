use std::collections::HashSet;

use rusqlite::{OptionalExtension, Transaction, params};
use unicode_normalization::UnicodeNormalization;
use uuid::Uuid;

use crate::{
    database::Database,
    error::AppError,
    sync_wire::{ItemMetadata, RemoteAttachment, RemoteDatedEntry, RemoteItem},
};

const LEASE_MILLIS: i64 = 60_000;
const MAX_RETRY_DELAY_MILLIS: i64 = 6 * 60 * 60 * 1_000;

#[derive(Debug, Clone)]
pub struct ClaimedOperation {
    pub operation_id: String,
    pub item_id: String,
    pub operation_type: String,
    pub target_revision: i64,
    pub attempt_count: i64,
    pub lease_token: String,
}

#[derive(Debug, Clone)]
pub struct SyncAttachmentSource {
    pub id: String,
    pub display_name: String,
    pub mime_type: String,
    pub file_size: i64,
    pub sha256: String,
    pub encrypted_relative_path: String,
    pub remote_path: Option<String>,
    pub upload_status: String,
    pub image_width: Option<i64>,
    pub image_height: Option<i64>,
    pub pdf_page_count: Option<i64>,
    pub created_at: i64,
}

#[derive(Debug, Clone)]
pub struct OutgoingItem {
    pub metadata: ItemMetadata,
    pub expected_version_token: Option<String>,
    pub deleted: bool,
    pub attachments: Vec<SyncAttachmentSource>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExistingAttachment {
    pub id: String,
    pub sha256: String,
    pub encrypted_relative_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoteExpectation {
    pub item_id: String,
    pub local_revision: Option<i64>,
    pub server_version_token: Option<String>,
    pub has_local_changes: bool,
    pub attachments: Vec<ExistingAttachment>,
}

#[derive(Debug, Clone)]
pub struct PreparedAttachment {
    pub local_id: String,
    pub encrypted_relative_path: String,
    pub reused: bool,
    pub remote: RemoteAttachment,
}

#[derive(Debug, Clone)]
pub enum PreparedRemoteChange {
    Ignored {
        remote: RemoteItem,
    },
    Deferred {
        remote: RemoteItem,
    },
    Delete {
        remote: RemoteItem,
        expectation: RemoteExpectation,
    },
    Upsert {
        remote: RemoteItem,
        expectation: RemoteExpectation,
        metadata: Box<ItemMetadata>,
        local_item_id: String,
        conflict_copy: bool,
        attachments: Vec<PreparedAttachment>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ObsoleteAttachment {
    pub id: String,
    pub encrypted_relative_path: String,
}

#[derive(Debug, Clone)]
pub struct AttachmentTombstone {
    pub attachment_id: String,
    pub operation_id: String,
    pub attempt_count: i64,
}

#[derive(Debug, Clone, Default)]
pub struct StoredSyncState {
    pub cursor: Option<String>,
    pub server_revision: Option<i64>,
    pub last_attempt_at: Option<i64>,
    pub last_success_at: Option<i64>,
}

#[derive(Debug, Clone, Default)]
pub struct QueueCounts {
    pub pending: i64,
    pub running: i64,
    pub retrying: i64,
    pub failed: i64,
}

#[derive(Clone)]
pub struct SyncStore {
    database: Database,
}

impl SyncStore {
    pub fn new(database: Database) -> Self {
        Self { database }
    }

    pub fn state(&self) -> Result<StoredSyncState, AppError> {
        self.database.with_connection(|connection| {
            connection
                .query_row(
                    r#"
                    SELECT incremental_cursor, server_revision, last_attempt_at, last_success_at
                    FROM sync_state WHERE scope = 'relay' LIMIT 1
                    "#,
                    [],
                    |row| {
                        Ok(StoredSyncState {
                            cursor: row.get(0)?,
                            server_revision: row.get(1)?,
                            last_attempt_at: row.get(2)?,
                            last_success_at: row.get(3)?,
                        })
                    },
                )
                .optional()
                .map(|value| value.unwrap_or_default())
                .map_err(AppError::from)
        })
    }

    pub fn mark_attempt(&self, now: i64) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            connection.execute(
                r#"
                INSERT INTO sync_state(scope, last_attempt_at)
                VALUES ('relay', ?1)
                ON CONFLICT(scope) DO UPDATE SET last_attempt_at = excluded.last_attempt_at
                "#,
                [now],
            )?;
            Ok(())
        })
    }

    pub fn queue_counts(&self) -> Result<QueueCounts, AppError> {
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
                    "#,
                    [],
                    |row| {
                        Ok(QueueCounts {
                            pending: row.get(0)?,
                            running: row.get(1)?,
                            retrying: row.get(2)?,
                            failed: row.get(3)?,
                        })
                    },
                )
                .map_err(AppError::from)
        })
    }

    pub fn claim_next(&self, now: i64) -> Result<Option<ClaimedOperation>, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            transaction.execute(
                r#"
                UPDATE sync_operations
                SET state = 'RETRY_WAIT', lease_token = NULL, lease_expires_at = NULL,
                    next_attempt_at = MIN(next_attempt_at, ?1), updated_at = ?1,
                    last_error_code = 'PROCESS_INTERRUPTED'
                WHERE state = 'RUNNING' AND lease_expires_at <= ?1
                "#,
                [now],
            )?;
            let candidate = transaction
                .query_row(
                    r#"
                    SELECT operation_id, item_id, operation_type, target_revision, attempt_count
                    FROM sync_operations
                    WHERE state IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= ?1
                    ORDER BY created_at, operation_id
                    LIMIT 1
                    "#,
                    [now],
                    |row| {
                        Ok(ClaimedOperation {
                            operation_id: row.get(0)?,
                            item_id: row.get(1)?,
                            operation_type: row.get(2)?,
                            target_revision: row.get(3)?,
                            attempt_count: row.get::<_, i64>(4)?.saturating_add(1),
                            lease_token: Uuid::new_v4().hyphenated().to_string(),
                        })
                    },
                )
                .optional()?;
            let Some(operation) = candidate else {
                transaction.commit()?;
                return Ok(None);
            };
            if transaction.execute(
                r#"
                UPDATE sync_operations
                SET state = 'RUNNING', attempt_count = ?1, lease_token = ?2,
                    lease_expires_at = ?3, updated_at = ?4, last_error_code = NULL
                WHERE operation_id = ?5 AND state IN ('PENDING', 'RETRY_WAIT')
                "#,
                params![
                    operation.attempt_count,
                    operation.lease_token,
                    now.saturating_add(LEASE_MILLIS),
                    now,
                    operation.operation_id,
                ],
            )? != 1
            {
                return Err(AppError::DatabaseLock);
            }
            transaction.execute(
                "UPDATE vault_items SET sync_status = 'SYNCING' WHERE id = ?1",
                [&operation.item_id],
            )?;
            transaction.commit()?;
            Ok(Some(operation))
        })
    }

    pub fn outgoing_item(&self, item_id: &str) -> Result<OutgoingItem, AppError> {
        self.database.with_connection(|connection| {
            let row = connection
                .query_row(
                    r#"
                    SELECT id, type, title, body, ocr_text, color, is_pinned, is_favorite,
                           is_archived, sort_position, created_at, updated_at, local_revision,
                           body_document_json, server_version_token, deleted_at
                    FROM vault_items WHERE id = ?1 LIMIT 1
                    "#,
                    [item_id],
                    |row| {
                        Ok((
                            row.get::<_, String>(0)?,
                            row.get::<_, String>(1)?,
                            row.get::<_, String>(2)?,
                            row.get::<_, String>(3)?,
                            row.get::<_, String>(4)?,
                            row.get::<_, String>(5)?,
                            row.get::<_, i64>(6)? != 0,
                            row.get::<_, i64>(7)? != 0,
                            row.get::<_, i64>(8)? != 0,
                            row.get::<_, i64>(9)?,
                            row.get::<_, i64>(10)?,
                            row.get::<_, i64>(11)?,
                            row.get::<_, i64>(12)?,
                            row.get::<_, Option<String>>(13)?,
                            row.get::<_, Option<String>>(14)?,
                            row.get::<_, Option<i64>>(15)?,
                        ))
                    },
                )
                .optional()?
                .ok_or(AppError::NotFound)?;
            let tags = {
                let mut statement = connection.prepare_cached(
                    r#"
                    SELECT t.name
                    FROM tags t INNER JOIN item_tags it ON it.tag_id = t.id
                    WHERE it.item_id = ?1
                    ORDER BY t.normalized_name, t.id
                    "#,
                )?;
                statement
                    .query_map([item_id], |row| row.get::<_, String>(0))?
                    .collect::<Result<Vec<_>, _>>()?
            };
            let attachments = load_attachment_sources(connection, item_id)?;
            let metadata = ItemMetadata {
                schema_version: crate::sync_wire::ITEM_SCHEMA_VERSION,
                id: row.0,
                item_type: row.1,
                title: row.2,
                body: row.3,
                ocr_text: row.4,
                color: row.5,
                is_pinned: row.6,
                is_favorite: row.7,
                is_archived: row.8,
                sort_position: row.9,
                created_at: row.10,
                updated_at: row.11,
                client_revision: row.12,
                body_document_json: row.13,
                tags,
                attachments: attachments
                    .iter()
                    .map(|value| RemoteAttachment {
                        id: value.id.clone(),
                        remote_path: value.remote_path.clone().unwrap_or_default(),
                        original_filename: value.display_name.clone(),
                        mime_type: value.mime_type.clone(),
                        file_size_bytes: value.file_size,
                        plaintext_sha256: value.sha256.clone(),
                        encryption_format_version: 1,
                        image_width: value.image_width,
                        image_height: value.image_height,
                        pdf_page_count: value.pdf_page_count,
                        created_at: value.created_at,
                    })
                    .collect(),
                dated_entries: load_remote_dated_entries(connection, item_id)?,
            };
            Ok(OutgoingItem {
                metadata,
                expected_version_token: row.14,
                deleted: row.15.is_some(),
                attachments,
            })
        })
    }

    pub fn mark_attachment_uploading(&self, attachment_id: &str) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            if connection.execute(
                "UPDATE attachments SET upload_status = 'UPLOADING' WHERE id = ?1",
                [attachment_id],
            )? != 1
            {
                return Err(AppError::NotFound);
            }
            Ok(())
        })
    }

    pub fn mark_attachment_uploaded(
        &self,
        attachment_id: &str,
        remote_path: &str,
    ) -> Result<(), AppError> {
        if remote_path != format!("/v1/attachments/{attachment_id}") {
            return Err(AppError::CorruptedSync);
        }
        self.database.with_connection(|connection| {
            if connection.execute(
                r#"
                UPDATE attachments
                SET remote_path = ?1, upload_status = 'UPLOADED'
                WHERE id = ?2
                "#,
                params![remote_path, attachment_id],
            )? != 1
            {
                return Err(AppError::NotFound);
            }
            Ok(())
        })
    }

    pub fn mark_attachment_retryable(&self, attachment_id: &str) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            connection.execute(
                r#"
                UPDATE attachments SET upload_status = 'FAILED_RETRYABLE'
                WHERE id = ?1 AND upload_status = 'UPLOADING'
                "#,
                [attachment_id],
            )?;
            Ok(())
        })
    }

    pub fn complete_operation(
        &self,
        operation: &ClaimedOperation,
        server_revision: i64,
        version_token: &str,
        now: i64,
    ) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            if transaction.execute(
                r#"
                DELETE FROM sync_operations
                WHERE operation_id = ?1 AND state = 'RUNNING' AND lease_token = ?2
                "#,
                params![operation.operation_id, operation.lease_token],
            )? != 1
            {
                return Err(AppError::SyncChangedLocally);
            }
            transaction.execute(
                r#"
                UPDATE vault_items
                SET remote_revision = ?1, last_synced_revision = ?2,
                    server_version_token = ?3,
                    sync_status = CASE
                        WHEN local_revision = ?2
                             AND NOT EXISTS(
                                 SELECT 1 FROM sync_operations s
                                 WHERE s.item_id = vault_items.id
                             )
                        THEN 'SYNCED' ELSE 'PENDING'
                    END
                WHERE id = ?4
                "#,
                params![
                    server_revision,
                    operation.target_revision,
                    version_token,
                    operation.item_id,
                ],
            )?;
            update_success_state(&transaction, server_revision, now)?;
            transaction.commit()?;
            Ok(())
        })
    }

    pub fn record_mutation_conflict(
        &self,
        operation: &ClaimedOperation,
        remote_revision: i64,
        _remote_version_token: &str,
        now: i64,
    ) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            transaction.execute(
                r#"
                UPDATE vault_items
                SET remote_revision = ?1, sync_status = 'CONFLICT'
                WHERE id = ?2
                "#,
                params![remote_revision, operation.item_id],
            )?;
            transaction.execute(
                r#"
                UPDATE sync_operations
                SET state = 'RETRY_WAIT', attempt_count = 0, next_attempt_at = ?1,
                    lease_token = NULL, lease_expires_at = NULL, updated_at = ?2,
                    last_error_code = 'CONFLICT_PRESERVED'
                WHERE operation_id = ?3 AND lease_token = ?4
                "#,
                params![
                    now.saturating_add(30_000),
                    now,
                    operation.operation_id,
                    operation.lease_token
                ],
            )?;
            transaction.commit()?;
            Ok(())
        })
    }

    pub fn fail_operation(
        &self,
        operation: &ClaimedOperation,
        now: i64,
        error_code: &str,
        permanent: bool,
    ) -> Result<(), AppError> {
        let next_attempt = now.saturating_add(retry_delay(operation.attempt_count));
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            transaction.execute(
                r#"
                UPDATE sync_operations
                SET state = ?1, next_attempt_at = ?2, lease_token = NULL,
                    lease_expires_at = NULL, updated_at = ?3, last_error_code = ?4
                WHERE operation_id = ?5 AND lease_token = ?6
                "#,
                params![
                    if permanent {
                        "FAILED_PERMANENT"
                    } else {
                        "RETRY_WAIT"
                    },
                    next_attempt,
                    now,
                    error_code,
                    operation.operation_id,
                    operation.lease_token,
                ],
            )?;
            transaction.execute(
                "UPDATE vault_items SET sync_status = ?1 WHERE id = ?2",
                params![
                    if permanent { "FAILED" } else { "PENDING" },
                    operation.item_id
                ],
            )?;
            transaction.commit()?;
            Ok(())
        })
    }

    pub fn remote_expectation(&self, item_id: &str) -> Result<RemoteExpectation, AppError> {
        self.database.with_connection(|connection| {
            let state = connection
                .query_row(
                    r#"
                    SELECT local_revision, server_version_token,
                           CASE
                               WHEN last_synced_revision IS NULL
                                    OR last_synced_revision != local_revision
                                    OR EXISTS(
                                        SELECT 1 FROM sync_operations s
                                        WHERE s.item_id = vault_items.id
                                    )
                               THEN 1 ELSE 0
                           END
                    FROM vault_items WHERE id = ?1 LIMIT 1
                    "#,
                    [item_id],
                    |row| {
                        Ok((
                            Some(row.get::<_, i64>(0)?),
                            row.get::<_, Option<String>>(1)?,
                            row.get::<_, i64>(2)? != 0,
                        ))
                    },
                )
                .optional()?
                .unwrap_or((None, None, false));
            let attachments = if state.0.is_some() {
                let mut statement = connection.prepare_cached(
                    r#"
                    SELECT id, sha256, encrypted_relative_path
                    FROM attachments WHERE parent_item_id = ?1 ORDER BY id
                    "#,
                )?;
                statement
                    .query_map([item_id], |row| {
                        Ok(ExistingAttachment {
                            id: row.get(0)?,
                            sha256: row.get(1)?,
                            encrypted_relative_path: row.get(2)?,
                        })
                    })?
                    .collect::<Result<Vec<_>, _>>()?
            } else {
                Vec::new()
            };
            Ok(RemoteExpectation {
                item_id: item_id.to_owned(),
                local_revision: state.0,
                server_version_token: state.1,
                has_local_changes: state.2,
                attachments,
            })
        })
    }
}

impl SyncStore {
    pub fn apply_remote_page(
        &self,
        changes: &[PreparedRemoteChange],
        next_cursor: Option<&str>,
        now: i64,
    ) -> Result<Vec<ObsoleteAttachment>, AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            let mut obsolete = Vec::new();
            let mut highest_revision = None;
            for change in changes {
                match change {
                    PreparedRemoteChange::Ignored { remote } => {
                        transaction.execute(
                            "DELETE FROM deferred_remote_items WHERE item_id = ?1",
                            [&remote.item_id],
                        )?;
                        highest_revision =
                            Some(highest_revision.unwrap_or(0).max(remote.server_revision));
                    }
                    PreparedRemoteChange::Deferred { remote } => {
                        store_deferred(&transaction, remote)?;
                        highest_revision =
                            Some(highest_revision.unwrap_or(0).max(remote.server_revision));
                    }
                    PreparedRemoteChange::Delete {
                        remote,
                        expectation,
                    } => {
                        verify_expectation(&transaction, expectation)?;
                        apply_remote_delete(&transaction, remote, expectation, now)?;
                        highest_revision =
                            Some(highest_revision.unwrap_or(0).max(remote.server_revision));
                    }
                    PreparedRemoteChange::Upsert {
                        remote,
                        expectation,
                        metadata,
                        local_item_id,
                        conflict_copy,
                        attachments,
                    } => {
                        verify_expectation(&transaction, expectation)?;
                        obsolete.extend(apply_remote_upsert(
                            &transaction,
                            remote,
                            metadata,
                            local_item_id,
                            *conflict_copy,
                            attachments,
                            now,
                        )?);
                        highest_revision =
                            Some(highest_revision.unwrap_or(0).max(remote.server_revision));
                    }
                }
            }
            transaction.execute(
                r#"
                INSERT INTO sync_state(
                    scope, incremental_cursor, server_revision, last_attempt_at, last_success_at
                ) VALUES ('relay', ?1, ?2, ?3, ?3)
                ON CONFLICT(scope) DO UPDATE SET
                    incremental_cursor = excluded.incremental_cursor,
                    server_revision = MAX(
                        COALESCE(sync_state.server_revision, 0),
                        COALESCE(excluded.server_revision, 0)
                    ),
                    last_attempt_at = excluded.last_attempt_at,
                    last_success_at = excluded.last_success_at
                "#,
                params![next_cursor, highest_revision, now],
            )?;
            transaction.commit()?;
            Ok(obsolete)
        })
    }

    pub fn cursor(&self) -> Result<Option<String>, AppError> {
        Ok(self.state()?.cursor)
    }

    pub fn next_attachment_tombstone(
        &self,
        now: i64,
    ) -> Result<Option<AttachmentTombstone>, AppError> {
        self.database.with_connection(|connection| {
            connection
                .query_row(
                    r#"
                    SELECT attachment_id, operation_id, attempt_count
                    FROM attachment_tombstones
                    WHERE next_attempt_at <= ?1
                    ORDER BY created_at, attachment_id
                    LIMIT 1
                    "#,
                    [now],
                    |row| {
                        Ok(AttachmentTombstone {
                            attachment_id: row.get(0)?,
                            operation_id: row.get(1)?,
                            attempt_count: row.get(2)?,
                        })
                    },
                )
                .optional()
                .map_err(AppError::from)
        })
    }

    pub fn complete_attachment_tombstone(&self, attachment_id: &str) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            connection.execute(
                "DELETE FROM attachment_tombstones WHERE attachment_id = ?1",
                [attachment_id],
            )?;
            Ok(())
        })
    }

    pub fn fail_attachment_tombstone(
        &self,
        attachment_id: &str,
        attempt_count: i64,
        now: i64,
        error_code: &str,
        permanent: bool,
    ) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            connection.execute(
                r#"
                UPDATE attachment_tombstones
                SET attempt_count = ?1, next_attempt_at = ?2, last_error_code = ?3
                WHERE attachment_id = ?4
                "#,
                params![
                    attempt_count.saturating_add(1),
                    if permanent {
                        i64::MAX
                    } else {
                        now.saturating_add(retry_delay(attempt_count.saturating_add(1)))
                    },
                    error_code,
                    attachment_id,
                ],
            )?;
            Ok(())
        })
    }

    pub fn prepare_new_remote(&self, now: i64) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            let transaction = connection.transaction()?;
            transaction.execute_batch(
                r#"
                DELETE FROM sync_operations;
                DELETE FROM attachment_tombstones;
                DELETE FROM deferred_remote_items;
                DELETE FROM sync_state;
                UPDATE attachments SET remote_path = NULL, upload_status = 'PENDING';
                UPDATE vault_items
                SET remote_revision = NULL, last_synced_revision = NULL,
                    server_version_token = NULL,
                    sync_status = CASE
                        WHEN sync_status = 'CONFLICT' THEN 'CONFLICT'
                        ELSE 'PENDING'
                    END;
                "#,
            )?;
            let items = {
                let mut statement = transaction.prepare_cached(
                    "SELECT id, local_revision, deleted_at FROM vault_items ORDER BY id",
                )?;
                statement
                    .query_map([], |row| {
                        Ok((
                            row.get::<_, String>(0)?,
                            row.get::<_, i64>(1)?,
                            row.get::<_, Option<i64>>(2)?.is_some(),
                        ))
                    })?
                    .collect::<Result<Vec<_>, _>>()?
            };
            for (item_id, revision, deleted) in items {
                enqueue_operation(
                    &transaction,
                    &item_id,
                    revision,
                    if deleted {
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

    pub fn resume_failed_operations(&self, now: i64) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            connection.execute(
                r#"
                UPDATE sync_operations
                SET state = 'PENDING', attempt_count = 0, next_attempt_at = ?1,
                    lease_token = NULL, lease_expires_at = NULL, updated_at = ?1,
                    last_error_code = NULL
                WHERE state = 'FAILED_PERMANENT'
                "#,
                [now],
            )?;
            connection.execute(
                "UPDATE vault_items SET sync_status = 'PENDING' WHERE sync_status = 'FAILED'",
                [],
            )?;
            connection.execute(
                r#"
                UPDATE attachment_tombstones
                SET attempt_count = 0, next_attempt_at = ?1, last_error_code = NULL
                WHERE next_attempt_at = ?2
                "#,
                params![now, i64::MAX],
            )?;
            Ok(())
        })
    }

    pub fn disconnect(&self) -> Result<(), AppError> {
        self.database.with_connection(|connection| {
            connection.execute_batch(
                r#"
                DELETE FROM sync_operations;
                DELETE FROM attachment_tombstones;
                DELETE FROM deferred_remote_items;
                DELETE FROM sync_state;
                UPDATE attachments SET remote_path = NULL, upload_status = 'PENDING';
                UPDATE vault_items
                SET remote_revision = NULL, last_synced_revision = NULL,
                    server_version_token = NULL,
                    sync_status = CASE
                        WHEN sync_status = 'CONFLICT' THEN 'CONFLICT'
                        ELSE 'LOCAL_ONLY'
                    END;
                "#,
            )?;
            Ok(())
        })
    }
}

fn verify_expectation(
    transaction: &Transaction<'_>,
    expectation: &RemoteExpectation,
) -> Result<(), AppError> {
    let current = transaction
        .query_row(
            r#"
            SELECT local_revision, server_version_token
            FROM vault_items WHERE id = ?1 LIMIT 1
            "#,
            [&expectation.item_id],
            |row| {
                Ok((
                    Some(row.get::<_, i64>(0)?),
                    row.get::<_, Option<String>>(1)?,
                ))
            },
        )
        .optional()?
        .unwrap_or((None, None));
    if current.0 != expectation.local_revision || current.1 != expectation.server_version_token {
        return Err(AppError::SyncChangedLocally);
    }
    Ok(())
}

fn store_deferred(transaction: &Transaction<'_>, remote: &RemoteItem) -> Result<(), AppError> {
    transaction.execute(
        r#"
        INSERT INTO deferred_remote_items(
            item_id, server_revision, version_token, deleted,
            encrypted_payload, ciphertext_sha256
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        ON CONFLICT(item_id) DO UPDATE SET
            server_revision = excluded.server_revision,
            version_token = excluded.version_token,
            deleted = excluded.deleted,
            encrypted_payload = excluded.encrypted_payload,
            ciphertext_sha256 = excluded.ciphertext_sha256
        "#,
        params![
            remote.item_id,
            remote.server_revision,
            remote.version_token,
            i64::from(remote.deleted),
            remote.encrypted_payload,
            remote.ciphertext_sha256,
        ],
    )?;
    Ok(())
}

fn apply_remote_delete(
    transaction: &Transaction<'_>,
    remote: &RemoteItem,
    expectation: &RemoteExpectation,
    now: i64,
) -> Result<(), AppError> {
    let Some(local_revision) = expectation.local_revision else {
        transaction.execute(
            "DELETE FROM deferred_remote_items WHERE item_id = ?1",
            [&remote.item_id],
        )?;
        return Ok(());
    };
    if expectation.has_local_changes {
        transaction.execute(
            r#"
            UPDATE vault_items
            SET remote_revision = ?1, server_version_token = ?2,
                sync_status = 'CONFLICT',
                conflict_origin_id = COALESCE(conflict_origin_id, id)
            WHERE id = ?3
            "#,
            params![remote.server_revision, remote.version_token, remote.item_id],
        )?;
        transaction.execute(
            "DELETE FROM sync_operations WHERE item_id = ?1",
            [&remote.item_id],
        )?;
    } else {
        transaction.execute(
            r#"
            UPDATE vault_items
            SET deleted_at = COALESCE(deleted_at, ?1), updated_at = MAX(updated_at, ?1),
                remote_revision = ?2, last_synced_revision = ?3,
                server_version_token = ?4, sync_status = 'SYNCED'
            WHERE id = ?5
            "#,
            params![
                now,
                remote.server_revision,
                local_revision,
                remote.version_token,
                remote.item_id,
            ],
        )?;
    }
    transaction.execute(
        "DELETE FROM deferred_remote_items WHERE item_id = ?1",
        [&remote.item_id],
    )?;
    Ok(())
}

fn apply_remote_upsert(
    transaction: &Transaction<'_>,
    remote: &RemoteItem,
    metadata: &ItemMetadata,
    local_item_id: &str,
    conflict_copy: bool,
    attachments: &[PreparedAttachment],
    now: i64,
) -> Result<Vec<ObsoleteAttachment>, AppError> {
    if !desktop_supported(metadata) {
        return Err(AppError::CorruptedSync);
    }
    let mut obsolete = Vec::new();
    if !conflict_copy {
        let retained: HashSet<&str> = attachments
            .iter()
            .filter(|value| value.reused)
            .map(|value| value.local_id.as_str())
            .collect();
        let existing = {
            let mut statement = transaction.prepare_cached(
                "SELECT id, encrypted_relative_path FROM attachments WHERE parent_item_id = ?1",
            )?;
            statement
                .query_map([local_item_id], |row| {
                    Ok(ObsoleteAttachment {
                        id: row.get(0)?,
                        encrypted_relative_path: row.get(1)?,
                    })
                })?
                .collect::<Result<Vec<_>, _>>()?
        };
        obsolete.extend(
            existing
                .into_iter()
                .filter(|value| !retained.contains(value.id.as_str())),
        );
        transaction.execute(
            "DELETE FROM vault_items_fts WHERE item_id = ?1",
            [local_item_id],
        )?;
        transaction.execute("DELETE FROM vault_items WHERE id = ?1", [local_item_id])?;
    }
    transaction.execute(
        r#"
        INSERT INTO vault_items(
            id, type, color, title, body, ocr_text, is_pinned, is_favorite, is_archived,
            created_at, updated_at, local_revision, remote_revision, last_synced_revision,
            server_version_token, sync_status, deleted_at, conflict_origin_id,
            body_document_json, sort_position
        ) VALUES (
            ?1, 'NOTE', ?2, ?3, ?4, ?5, ?6, ?7, ?8,
            ?9, ?10, ?11, ?12, ?11, ?13, ?14, NULL, ?15, ?16, ?17
        )
        "#,
        params![
            local_item_id,
            metadata.color,
            metadata.title,
            metadata.body,
            metadata.ocr_text,
            i64::from(metadata.is_pinned),
            i64::from(metadata.is_favorite),
            i64::from(metadata.is_archived),
            metadata.created_at,
            metadata.updated_at,
            metadata.client_revision.max(1),
            remote.server_revision,
            remote.version_token,
            if conflict_copy { "CONFLICT" } else { "SYNCED" },
            if conflict_copy {
                Some(remote.item_id.as_str())
            } else {
                None
            },
            metadata.body_document_json,
            metadata.sort_position,
        ],
    )?;
    insert_tags(transaction, local_item_id, &metadata.tags, now)?;
    insert_attachments(transaction, local_item_id, attachments)?;
    insert_dated_entries(transaction, local_item_id, &metadata.dated_entries)?;
    refresh_fts(transaction, local_item_id, &metadata.title, &metadata.body)?;
    if conflict_copy {
        transaction.execute(
            r#"
            UPDATE vault_items
            SET remote_revision = ?1, server_version_token = ?2, sync_status = 'CONFLICT'
            WHERE id = ?3
            "#,
            params![remote.server_revision, remote.version_token, remote.item_id],
        )?;
    }
    if !conflict_copy {
        transaction.execute(
            "DELETE FROM sync_operations WHERE item_id = ?1",
            [local_item_id],
        )?;
    }
    transaction.execute(
        "DELETE FROM deferred_remote_items WHERE item_id = ?1",
        [&remote.item_id],
    )?;
    Ok(obsolete)
}

fn insert_tags(
    transaction: &Transaction<'_>,
    item_id: &str,
    tags: &[String],
    now: i64,
) -> Result<(), AppError> {
    let mut seen = HashSet::new();
    for raw in tags {
        let name = raw.nfkc().collect::<String>().trim().to_owned();
        let normalized = name.to_lowercase();
        if name.is_empty()
            || name.chars().count() > 100
            || normalized.chars().count() > 100
            || !seen.insert(normalized.clone())
        {
            return Err(AppError::CorruptedSync);
        }
        let existing = transaction
            .query_row(
                "SELECT id FROM tags WHERE normalized_name = ?1 LIMIT 1",
                [&normalized],
                |row| row.get::<_, String>(0),
            )
            .optional()?;
        let tag_id = existing.unwrap_or_else(|| Uuid::new_v4().hyphenated().to_string());
        transaction.execute(
            r#"
            INSERT INTO tags(id, name, normalized_name, created_at)
            VALUES (?1, ?2, ?3, ?4)
            ON CONFLICT(normalized_name) DO NOTHING
            "#,
            params![tag_id, name, normalized, now],
        )?;
        let actual_id: String = transaction.query_row(
            "SELECT id FROM tags WHERE normalized_name = ?1",
            [&normalized],
            |row| row.get(0),
        )?;
        transaction.execute(
            "INSERT OR IGNORE INTO item_tags(item_id, tag_id) VALUES (?1, ?2)",
            params![item_id, actual_id],
        )?;
    }
    Ok(())
}

fn insert_attachments(
    transaction: &Transaction<'_>,
    item_id: &str,
    attachments: &[PreparedAttachment],
) -> Result<(), AppError> {
    for attachment in attachments {
        transaction.execute(
            r#"
            INSERT INTO attachments(
                id, parent_item_id, display_name, mime_type, file_size, sha256,
                encrypted_relative_path, encryption_format_version, created_at,
                image_width, image_height, pdf_page_count, ocr_state, ocr_text,
                remote_path, upload_status
            ) VALUES (
                ?1, ?2, ?3, ?4, ?5, ?6, ?7, 1, ?8,
                ?9, ?10, ?11, 'PENDING', '', ?12, 'UPLOADED'
            )
            "#,
            params![
                attachment.local_id,
                item_id,
                attachment.remote.original_filename,
                attachment.remote.mime_type,
                attachment.remote.file_size_bytes,
                attachment.remote.plaintext_sha256,
                attachment.encrypted_relative_path,
                attachment.remote.created_at,
                attachment.remote.image_width,
                attachment.remote.image_height,
                attachment.remote.pdf_page_count,
                attachment.remote.remote_path,
            ],
        )?;
    }
    Ok(())
}

fn insert_dated_entries(
    transaction: &Transaction<'_>,
    item_id: &str,
    entries: &[RemoteDatedEntry],
) -> Result<(), AppError> {
    for entry in entries {
        let entry_id = if transaction
            .query_row(
                "SELECT 1 FROM dated_entries WHERE id = ?1",
                [&entry.id],
                |_| Ok(()),
            )
            .optional()?
            .is_some()
        {
            Uuid::new_v4().hyphenated().to_string()
        } else {
            entry.id.clone()
        };
        transaction.execute(
            r#"
            INSERT INTO dated_entries(
                id, item_id, entry_type, label, occurrence_at, is_all_day, time_zone_id,
                recurrence_unit, recurrence_interval, completed_at, created_at, updated_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
            "#,
            params![
                entry_id,
                item_id,
                entry.entry_type,
                entry.label,
                entry.occurrence_at,
                i64::from(entry.is_all_day),
                entry.time_zone_id,
                entry.recurrence_unit,
                entry.recurrence_interval,
                entry.completed_at,
                entry.created_at,
                entry.updated_at,
            ],
        )?;
        for lead_time in &entry.alert_lead_times_minutes {
            transaction.execute(
                r#"
                INSERT INTO dated_entry_alerts(
                    id, entry_id, lead_time_minutes, snoozed_until, created_at
                ) VALUES (?1, ?2, ?3, NULL, ?4)
                "#,
                params![
                    Uuid::new_v4().hyphenated().to_string(),
                    entry_id,
                    lead_time,
                    entry.created_at,
                ],
            )?;
        }
    }
    Ok(())
}

fn refresh_fts(
    transaction: &Transaction<'_>,
    item_id: &str,
    title: &str,
    body: &str,
) -> Result<(), AppError> {
    let filenames = {
        let mut statement = transaction.prepare_cached(
            "SELECT display_name FROM attachments WHERE parent_item_id = ?1 ORDER BY created_at, id",
        )?;
        statement
            .query_map([item_id], |row| row.get::<_, String>(0))?
            .collect::<Result<Vec<_>, _>>()?
            .join("\n")
    };
    transaction.execute(
        r#"
        INSERT INTO vault_items_fts(item_id, title, body, attachment_filenames)
        VALUES (?1, ?2, ?3, ?4)
        "#,
        params![item_id, title, body, filenames],
    )?;
    Ok(())
}

fn enqueue_operation(
    transaction: &Transaction<'_>,
    item_id: &str,
    target_revision: i64,
    operation_type: &str,
    now: i64,
) -> Result<(), AppError> {
    transaction.execute(
        r#"
        INSERT INTO sync_operations(
            operation_id, dedupe_key, item_id, operation_type, target_revision, state,
            attempt_count, next_attempt_at, lease_token, lease_expires_at, created_at,
            updated_at, last_error_code
        ) VALUES (?1, ?2, ?3, ?4, ?5, 'PENDING', 0, ?6, NULL, NULL, ?6, ?6, NULL)
        "#,
        params![
            Uuid::new_v4().hyphenated().to_string(),
            format!("item:{item_id}"),
            item_id,
            operation_type,
            target_revision,
            now,
        ],
    )?;
    Ok(())
}

pub fn desktop_supported(metadata: &ItemMetadata) -> bool {
    metadata.item_type == "NOTE"
        && metadata.title.chars().count() <= 500
        && metadata.body.chars().count() <= 100_000
        && metadata.ocr_text.chars().count() <= 1_500_000
        && metadata.tags.len() <= 64
        && metadata.tags.iter().all(|tag| tag.chars().count() <= 100)
        && metadata.body_document_json.as_ref().is_none_or(|value| {
            value.chars().count() <= 100_000
                && serde_json::from_str::<crate::models::NoteBodyDocument>(value).is_ok()
        })
        && metadata.dated_entries.iter().all(|entry| {
            entry.label.chars().count() <= 500 && entry.time_zone_id.chars().count() <= 100
        })
}

fn load_attachment_sources(
    connection: &rusqlite::Connection,
    item_id: &str,
) -> Result<Vec<SyncAttachmentSource>, AppError> {
    let mut statement = connection.prepare_cached(
        r#"
        SELECT id, display_name, mime_type, file_size, sha256,
               encrypted_relative_path, remote_path, upload_status, image_width,
               image_height, pdf_page_count, created_at
        FROM attachments WHERE parent_item_id = ?1
        ORDER BY created_at, id
        "#,
    )?;
    statement
        .query_map([item_id], |row| {
            Ok(SyncAttachmentSource {
                id: row.get(0)?,
                display_name: row.get(1)?,
                mime_type: row.get(2)?,
                file_size: row.get(3)?,
                sha256: row.get(4)?,
                encrypted_relative_path: row.get(5)?,
                remote_path: row.get(6)?,
                upload_status: row.get(7)?,
                image_width: row.get(8)?,
                image_height: row.get(9)?,
                pdf_page_count: row.get(10)?,
                created_at: row.get(11)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()
        .map_err(AppError::from)
}

fn load_remote_dated_entries(
    connection: &rusqlite::Connection,
    item_id: &str,
) -> Result<Vec<RemoteDatedEntry>, AppError> {
    let mut statement = connection.prepare_cached(
        r#"
        SELECT id, entry_type, label, occurrence_at, is_all_day, time_zone_id,
               recurrence_unit, recurrence_interval, completed_at, created_at, updated_at
        FROM dated_entries WHERE item_id = ?1 ORDER BY occurrence_at, id
        "#,
    )?;
    let rows = statement
        .query_map([item_id], |row| {
            Ok(RemoteDatedEntry {
                id: row.get(0)?,
                entry_type: row.get(1)?,
                label: row.get(2)?,
                occurrence_at: row.get(3)?,
                is_all_day: row.get::<_, i64>(4)? != 0,
                time_zone_id: row.get(5)?,
                recurrence_unit: row.get(6)?,
                recurrence_interval: row.get(7)?,
                completed_at: row.get(8)?,
                created_at: row.get(9)?,
                updated_at: row.get(10)?,
                alert_lead_times_minutes: Vec::new(),
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    let mut result = Vec::with_capacity(rows.len());
    for mut entry in rows {
        let mut alert_statement = connection.prepare_cached(
            r#"
            SELECT lead_time_minutes FROM dated_entry_alerts
            WHERE entry_id = ?1 ORDER BY lead_time_minutes, id
            "#,
        )?;
        entry.alert_lead_times_minutes = alert_statement
            .query_map([&entry.id], |row| row.get::<_, i64>(0))?
            .collect::<Result<Vec<_>, _>>()?;
        result.push(entry);
    }
    Ok(result)
}

fn update_success_state(
    transaction: &Transaction<'_>,
    server_revision: i64,
    now: i64,
) -> Result<(), AppError> {
    transaction.execute(
        r#"
        INSERT INTO sync_state(scope, server_revision, last_attempt_at, last_success_at)
        VALUES ('relay', ?1, ?2, ?2)
        ON CONFLICT(scope) DO UPDATE SET
            server_revision = MAX(COALESCE(sync_state.server_revision, 0), excluded.server_revision),
            last_attempt_at = excluded.last_attempt_at,
            last_success_at = excluded.last_success_at
        "#,
        params![server_revision, now],
    )?;
    Ok(())
}

fn retry_delay(attempt_count: i64) -> i64 {
    let exponent = u32::try_from(attempt_count.saturating_sub(1).clamp(0, 20)).unwrap_or(20);
    5_000_i64
        .saturating_mul(2_i64.saturating_pow(exponent))
        .min(MAX_RETRY_DELAY_MILLIS)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        repository::{SqliteVaultRepository, VaultRepository},
        sync_wire::ITEM_SCHEMA_VERSION,
    };

    fn queued_note() -> (Database, SqliteVaultRepository, SyncStore, String) {
        let database = Database::open_in_memory().expect("database should open");
        let repository = SqliteVaultRepository::new(database.clone());
        let id = repository
            .create_note("Local title", "Local unsynchronized body", 1_000)
            .expect("note should be queued");
        let store = SyncStore::new(database.clone());
        (database, repository, store, id)
    }

    #[test]
    fn expired_lease_is_recovered_and_retry_uses_exponential_delay() {
        let (_database, _repository, store, id) = queued_note();
        let first = store
            .claim_next(1_001)
            .expect("queue should be readable")
            .expect("operation should be claimed");
        assert_eq!(first.item_id, id);
        assert_eq!(first.attempt_count, 1);

        let recovery_time = 1_001 + LEASE_MILLIS + 1;
        let recovered = store
            .claim_next(recovery_time)
            .expect("queue should be readable")
            .expect("expired operation should be recovered");
        assert_eq!(recovered.operation_id, first.operation_id);
        assert_ne!(recovered.lease_token, first.lease_token);
        assert_eq!(recovered.attempt_count, 2);

        store
            .fail_operation(&recovered, recovery_time, "NETWORK", false)
            .expect("retry should be persisted");
        assert!(
            store
                .claim_next(recovery_time + 9_999)
                .expect("queue should be readable")
                .is_none()
        );
        assert!(
            store
                .claim_next(recovery_time + 10_000)
                .expect("queue should be readable")
                .is_some()
        );
    }

    #[test]
    fn concurrent_remote_edit_preserves_local_and_remote_copies() {
        let (database, repository, store, origin_id) = queued_note();
        let expectation = store
            .remote_expectation(&origin_id)
            .expect("expectation should load");
        assert!(expectation.has_local_changes);
        let conflict_id = Uuid::new_v4().hyphenated().to_string();
        let remote = RemoteItem {
            item_id: origin_id.clone(),
            server_revision: 17,
            version_token: "server-token-17".to_owned(),
            deleted: false,
            encrypted_payload: Some("authenticated-by-sync-engine".to_owned()),
            ciphertext_sha256: Some("0".repeat(64)),
        };
        let metadata = ItemMetadata {
            schema_version: ITEM_SCHEMA_VERSION,
            id: origin_id.clone(),
            item_type: "NOTE".to_owned(),
            title: "Remote title".to_owned(),
            body: "Remote concurrent body".to_owned(),
            ocr_text: String::new(),
            color: "BLUE".to_owned(),
            is_pinned: false,
            is_favorite: false,
            is_archived: false,
            sort_position: 8,
            created_at: 900,
            updated_at: 1_100,
            client_revision: 4,
            body_document_json: None,
            tags: Vec::new(),
            attachments: Vec::new(),
            dated_entries: Vec::new(),
        };
        let change = PreparedRemoteChange::Upsert {
            remote,
            expectation,
            metadata: Box::new(metadata),
            local_item_id: conflict_id.clone(),
            conflict_copy: true,
            attachments: Vec::new(),
        };

        store
            .apply_remote_page(&[change], Some("cursor-17"), 1_200)
            .expect("conflict page should commit");

        assert_eq!(
            repository
                .get_note(&origin_id)
                .expect("local origin should remain")
                .body,
            "Local unsynchronized body"
        );
        let remote_copy = repository
            .get_note(&conflict_id)
            .expect("remote conflict copy should exist");
        assert_eq!(remote_copy.body, "Remote concurrent body");
        assert_eq!(remote_copy.sync_status, "CONFLICT");
        database
            .with_connection(|connection| {
                let (origin_status, conflict_origin): (String, Option<String>) = connection
                    .query_row(
                        "SELECT sync_status, conflict_origin_id FROM vault_items WHERE id = ?1",
                        [&origin_id],
                        |row| Ok((row.get(0)?, row.get(1)?)),
                    )?;
                let queued: i64 = connection.query_row(
                    "SELECT count(*) FROM sync_operations WHERE item_id = ?1",
                    [&origin_id],
                    |row| row.get(0),
                )?;
                let copy_origin: Option<String> = connection.query_row(
                    "SELECT conflict_origin_id FROM vault_items WHERE id = ?1",
                    [&conflict_id],
                    |row| row.get(0),
                )?;
                assert_eq!(origin_status, "CONFLICT");
                assert_eq!(conflict_origin, None);
                assert_eq!(copy_origin.as_deref(), Some(origin_id.as_str()));
                assert_eq!(queued, 1);
                Ok(())
            })
            .expect("conflict metadata should be readable");
        let state = store.state().expect("sync state should load");
        assert_eq!(state.cursor.as_deref(), Some("cursor-17"));
        assert_eq!(state.server_revision, Some(17));
    }

    #[test]
    fn retry_delay_is_bounded() {
        assert_eq!(retry_delay(1), 5_000);
        assert_eq!(retry_delay(2), 10_000);
        assert_eq!(retry_delay(i64::MAX), MAX_RETRY_DELAY_MILLIS);
    }
}
