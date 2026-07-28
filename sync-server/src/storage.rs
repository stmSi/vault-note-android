use std::{
    collections::HashSet,
    fs::{self, File},
    path::{Path, PathBuf},
    time::Duration,
};

use rusqlite::{Connection, OptionalExtension, Transaction, params};

use crate::{
    config::{RelayConfig, sha256_hex},
    error::RelayError,
    model::{
        AttachmentDeleteResponse, AttachmentReceipt, ChangePage, KeyCheckEnvelope, MutationOutcome,
        MutationResponse, RemoteItem, StoredMutation,
    },
};

const SCHEMA_VERSION: i64 = 1;
const ITEM_OPERATION_UPSERT: &str = "UPSERT_ITEM";
const ITEM_OPERATION_DELETE: &str = "DELETE_ITEM";
const ATTACHMENT_OPERATION_UPLOAD: &str = "UPLOAD_ATTACHMENT";
const ATTACHMENT_OPERATION_DELETE: &str = "DELETE_ATTACHMENT";

#[derive(Debug, Clone)]
pub struct Storage {
    database_path: PathBuf,
    attachments_directory: PathBuf,
}

#[derive(Debug, Clone)]
pub struct AttachmentRecord {
    pub attachment_id: String,
    pub ciphertext_sha256: String,
    pub ciphertext_size: u64,
    pub stored_filename: String,
}

#[derive(Debug)]
pub enum StoredOperation<T> {
    Complete(T),
    IdempotencyMismatch,
}

#[derive(Debug)]
pub enum AttachmentCommit {
    Complete(AttachmentReceipt),
    IdempotencyMismatch,
    ImmutableConflict,
}

impl Storage {
    pub fn open(data_directory: &Path, config: &RelayConfig) -> Result<Self, RelayError> {
        let storage = Self {
            database_path: config.database_path(data_directory),
            attachments_directory: config.attachments_directory(data_directory),
        };
        fs::create_dir_all(&storage.attachments_directory)?;
        set_private_directory_permissions(&storage.attachments_directory)?;
        storage.initialize_database()?;
        storage.cleanup_abandoned_files()?;
        Ok(storage)
    }

    pub fn get_key_check(&self) -> Result<Option<KeyCheckEnvelope>, RelayError> {
        let connection = self.connection()?;
        connection
            .query_row(
                "SELECT encrypted_key_check, ciphertext_sha256 FROM key_check WHERE singleton_id = 1",
                [],
                |row| {
                    Ok(KeyCheckEnvelope {
                        encrypted_key_check: row.get(0)?,
                        ciphertext_sha256: row.get(1)?,
                    })
                },
            )
            .optional()
            .map_err(RelayError::from)
    }

    pub fn initialize_key_check(
        &self,
        envelope: &KeyCheckEnvelope,
    ) -> Result<KeyCheckInitialization, RelayError> {
        let connection = self.connection()?;
        let changed = connection.execute(
            r#"
            INSERT INTO key_check(singleton_id, encrypted_key_check, ciphertext_sha256)
            VALUES (1, ?1, ?2)
            ON CONFLICT(singleton_id) DO NOTHING
            "#,
            params![envelope.encrypted_key_check, envelope.ciphertext_sha256],
        )?;
        if changed == 1 {
            return Ok(KeyCheckInitialization::Created);
        }
        let current = self.get_key_check()?;
        if current.as_ref() == Some(envelope) {
            Ok(KeyCheckInitialization::AlreadyIdentical)
        } else {
            Ok(KeyCheckInitialization::AlreadyDifferent)
        }
    }

    pub fn upsert_item(
        &self,
        operation_id: &str,
        item_id: &str,
        request_hash: &str,
        expected_version_token: Option<&str>,
        encrypted_payload: &str,
        ciphertext_sha256: &str,
    ) -> Result<StoredOperation<StoredMutation>, RelayError> {
        self.mutate_item(
            operation_id,
            item_id,
            ITEM_OPERATION_UPSERT,
            request_hash,
            expected_version_token,
            Some((encrypted_payload, ciphertext_sha256)),
        )
    }

    pub fn delete_item(
        &self,
        operation_id: &str,
        item_id: &str,
        request_hash: &str,
        expected_version_token: Option<&str>,
    ) -> Result<StoredOperation<StoredMutation>, RelayError> {
        self.mutate_item(
            operation_id,
            item_id,
            ITEM_OPERATION_DELETE,
            request_hash,
            expected_version_token,
            None,
        )
    }

    pub fn pull_changes(&self, after_revision: i64, limit: u32) -> Result<ChangePage, RelayError> {
        let connection = self.connection()?;
        let mut statement = connection.prepare_cached(
            r#"
            SELECT item_id, server_revision, version_token, deleted,
                   encrypted_payload, ciphertext_sha256
            FROM changes
            WHERE server_revision > ?1
            ORDER BY server_revision ASC
            LIMIT ?2
            "#,
        )?;
        let changes = statement
            .query_map(params![after_revision, i64::from(limit)], map_remote_item)?
            .collect::<Result<Vec<_>, _>>()?;
        let last_revision = changes
            .last()
            .map(|change| change.server_revision)
            .unwrap_or(after_revision);
        let has_more = connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM changes WHERE server_revision > ?1)",
            [last_revision],
            |row| row.get::<_, bool>(0),
        )?;
        Ok(ChangePage {
            next_cursor: if changes.is_empty() {
                if after_revision == 0 {
                    None
                } else {
                    Some(after_revision.to_string())
                }
            } else {
                Some(last_revision.to_string())
            },
            changes,
            has_more,
        })
    }

    pub fn attachment_record(
        &self,
        attachment_id: &str,
    ) -> Result<Option<AttachmentRecord>, RelayError> {
        let connection = self.connection()?;
        query_attachment(&connection, attachment_id).map_err(RelayError::from)
    }

    pub fn attachment_file(&self, record: &AttachmentRecord) -> PathBuf {
        self.attachments_directory.join(&record.stored_filename)
    }

    pub fn new_pending_attachment_path(&self) -> PathBuf {
        self.attachments_directory
            .join(format!(".pending-{}.tmp", uuid::Uuid::new_v4()))
    }

    pub fn existing_attachment_upload(
        &self,
        operation_id: &str,
        attachment_id: &str,
        request_hash: &str,
    ) -> Result<Option<StoredOperation<AttachmentReceipt>>, RelayError> {
        let connection = self.connection()?;
        let operation = query_attachment_operation(
            &connection,
            operation_id,
            ATTACHMENT_OPERATION_UPLOAD,
            attachment_id,
        )?;
        match operation {
            None => Ok(None),
            Some((stored_hash, _)) if stored_hash != request_hash => {
                Ok(Some(StoredOperation::IdempotencyMismatch))
            }
            Some((_, response_json)) => Ok(Some(StoredOperation::Complete(serde_json::from_str(
                &response_json,
            )?))),
        }
    }

    pub fn commit_attachment_upload(
        &self,
        operation_id: &str,
        attachment_id: &str,
        request_hash: &str,
        ciphertext_sha256: &str,
        ciphertext_size: u64,
        pending_file: &Path,
    ) -> Result<AttachmentCommit, RelayError> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction()?;
        if let Some((stored_hash, response_json)) = query_attachment_operation(
            &transaction,
            operation_id,
            ATTACHMENT_OPERATION_UPLOAD,
            attachment_id,
        )? {
            if stored_hash != request_hash {
                return Ok(AttachmentCommit::IdempotencyMismatch);
            }
            let receipt = serde_json::from_str(&response_json)?;
            return Ok(AttachmentCommit::Complete(receipt));
        }

        if let Some(existing) = query_attachment(&transaction, attachment_id)? {
            if existing.ciphertext_sha256 != ciphertext_sha256
                || existing.ciphertext_size != ciphertext_size
            {
                return Ok(AttachmentCommit::ImmutableConflict);
            }
            let receipt = existing.to_receipt();
            insert_attachment_operation(
                &transaction,
                operation_id,
                ATTACHMENT_OPERATION_UPLOAD,
                attachment_id,
                request_hash,
                &receipt,
            )?;
            transaction.commit()?;
            return Ok(AttachmentCommit::Complete(receipt));
        }

        let stored_filename = attachment_filename(attachment_id);
        let final_path = self.attachments_directory.join(&stored_filename);
        if final_path.exists() {
            return Err(RelayError::InvalidStoragePath);
        }
        fs::rename(pending_file, &final_path)?;
        sync_directory(&self.attachments_directory)?;
        let inserted = transaction.execute(
            r#"
            INSERT INTO attachments(
                attachment_id, ciphertext_sha256, ciphertext_size, stored_filename
            ) VALUES (?1, ?2, ?3, ?4)
            "#,
            params![
                attachment_id,
                ciphertext_sha256,
                i64::try_from(ciphertext_size).map_err(|_| RelayError::InvalidConfiguration)?,
                stored_filename,
            ],
        );
        if let Err(error) = inserted {
            let _ = fs::remove_file(&final_path);
            return Err(RelayError::Database(error));
        }
        let receipt = AttachmentReceipt {
            attachment_id: attachment_id.to_owned(),
            ciphertext_sha256: ciphertext_sha256.to_owned(),
            ciphertext_size,
            remote_path: format!("/v1/attachments/{attachment_id}"),
        };
        if let Err(error) = insert_attachment_operation(
            &transaction,
            operation_id,
            ATTACHMENT_OPERATION_UPLOAD,
            attachment_id,
            request_hash,
            &receipt,
        )
        .and_then(|()| transaction.commit().map_err(RelayError::from))
        {
            let _ = fs::remove_file(&final_path);
            return Err(error);
        }
        Ok(AttachmentCommit::Complete(receipt))
    }

    pub fn delete_attachment(
        &self,
        operation_id: &str,
        attachment_id: &str,
        request_hash: &str,
    ) -> Result<StoredOperation<AttachmentDeleteResponse>, RelayError> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction()?;
        if let Some((stored_hash, response_json)) = query_attachment_operation(
            &transaction,
            operation_id,
            ATTACHMENT_OPERATION_DELETE,
            attachment_id,
        )? {
            if stored_hash != request_hash {
                return Ok(StoredOperation::IdempotencyMismatch);
            }
            return Ok(StoredOperation::Complete(serde_json::from_str(
                &response_json,
            )?));
        }
        let existing = query_attachment(&transaction, attachment_id)?;
        transaction.execute(
            "DELETE FROM attachments WHERE attachment_id = ?1",
            [attachment_id],
        )?;
        let response = AttachmentDeleteResponse {
            attachment_id: attachment_id.to_owned(),
            deleted: existing.is_some(),
        };
        insert_attachment_operation(
            &transaction,
            operation_id,
            ATTACHMENT_OPERATION_DELETE,
            attachment_id,
            request_hash,
            &response,
        )?;
        transaction.commit()?;
        if let Some(record) = existing {
            let _ = fs::remove_file(self.attachment_file(&record));
        }
        Ok(StoredOperation::Complete(response))
    }

    fn mutate_item(
        &self,
        operation_id: &str,
        item_id: &str,
        operation_kind: &str,
        request_hash: &str,
        expected_version_token: Option<&str>,
        payload: Option<(&str, &str)>,
    ) -> Result<StoredOperation<StoredMutation>, RelayError> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction()?;
        if let Some(stored) = query_item_operation(
            &transaction,
            operation_id,
            operation_kind,
            item_id,
            request_hash,
        )? {
            return Ok(stored);
        }
        if item_operation_exists(&transaction, operation_id)? {
            return Ok(StoredOperation::IdempotencyMismatch);
        }

        let current = query_remote_item(&transaction, item_id)?;
        let token_matches = match (&current, expected_version_token) {
            (None, None) => true,
            (Some(remote), Some(expected)) => remote.version_token == expected,
            _ => false,
        };
        if !token_matches {
            let response = MutationResponse {
                outcome: MutationOutcome::Conflict,
                server_revision: None,
                version_token: None,
                remote: current,
            };
            let stored = StoredMutation {
                status_code: 409,
                response,
            };
            insert_item_operation(
                &transaction,
                operation_id,
                operation_kind,
                item_id,
                request_hash,
                &stored,
            )?;
            transaction.commit()?;
            return Ok(StoredOperation::Complete(stored));
        }

        let version_token = uuid::Uuid::new_v4().hyphenated().to_string();
        let deleted = payload.is_none();
        let (encrypted_payload, ciphertext_sha256) = payload
            .map(|(encrypted, checksum)| (Some(encrypted), Some(checksum)))
            .unwrap_or((None, None));
        transaction.execute(
            r#"
            INSERT INTO changes(
                item_id, version_token, deleted, encrypted_payload, ciphertext_sha256
            ) VALUES (?1, ?2, ?3, ?4, ?5)
            "#,
            params![
                item_id,
                version_token,
                deleted,
                encrypted_payload,
                ciphertext_sha256,
            ],
        )?;
        let server_revision = transaction.last_insert_rowid();
        transaction.execute(
            r#"
            INSERT INTO items(
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
                item_id,
                server_revision,
                version_token,
                deleted,
                encrypted_payload,
                ciphertext_sha256,
            ],
        )?;
        let response = MutationResponse {
            outcome: MutationOutcome::Applied,
            server_revision: Some(server_revision),
            version_token: Some(version_token),
            remote: None,
        };
        let stored = StoredMutation {
            status_code: 200,
            response,
        };
        insert_item_operation(
            &transaction,
            operation_id,
            operation_kind,
            item_id,
            request_hash,
            &stored,
        )?;
        transaction.commit()?;
        Ok(StoredOperation::Complete(stored))
    }

    fn initialize_database(&self) -> Result<(), RelayError> {
        let connection = self.connection()?;
        let version: i64 = connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
        if version > SCHEMA_VERSION {
            return Err(RelayError::InvalidConfiguration);
        }
        if version == 0 {
            connection.execute_batch(
                r#"
                BEGIN IMMEDIATE;
                CREATE TABLE items (
                    item_id TEXT NOT NULL PRIMARY KEY,
                    server_revision INTEGER NOT NULL,
                    version_token TEXT NOT NULL,
                    deleted INTEGER NOT NULL CHECK(deleted IN (0, 1)),
                    encrypted_payload TEXT,
                    ciphertext_sha256 TEXT,
                    CHECK(
                        (deleted = 1 AND encrypted_payload IS NULL AND ciphertext_sha256 IS NULL)
                        OR
                        (deleted = 0 AND encrypted_payload IS NOT NULL AND ciphertext_sha256 IS NOT NULL)
                    )
                );
                CREATE TABLE changes (
                    server_revision INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id TEXT NOT NULL,
                    version_token TEXT NOT NULL,
                    deleted INTEGER NOT NULL CHECK(deleted IN (0, 1)),
                    encrypted_payload TEXT,
                    ciphertext_sha256 TEXT,
                    CHECK(
                        (deleted = 1 AND encrypted_payload IS NULL AND ciphertext_sha256 IS NULL)
                        OR
                        (deleted = 0 AND encrypted_payload IS NOT NULL AND ciphertext_sha256 IS NOT NULL)
                    )
                );
                CREATE INDEX index_changes_item_id ON changes(item_id, server_revision);
                CREATE TABLE item_operations (
                    operation_id TEXT NOT NULL PRIMARY KEY,
                    operation_kind TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    request_sha256 TEXT NOT NULL,
                    status_code INTEGER NOT NULL,
                    response_json TEXT NOT NULL
                );
                CREATE TABLE key_check (
                    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                    encrypted_key_check TEXT NOT NULL,
                    ciphertext_sha256 TEXT NOT NULL
                );
                CREATE TABLE attachments (
                    attachment_id TEXT NOT NULL PRIMARY KEY,
                    ciphertext_sha256 TEXT NOT NULL,
                    ciphertext_size INTEGER NOT NULL CHECK(ciphertext_size > 0),
                    stored_filename TEXT NOT NULL UNIQUE
                );
                CREATE TABLE attachment_operations (
                    operation_id TEXT NOT NULL PRIMARY KEY,
                    operation_kind TEXT NOT NULL,
                    attachment_id TEXT NOT NULL,
                    request_sha256 TEXT NOT NULL,
                    response_json TEXT NOT NULL
                );
                PRAGMA user_version = 1;
                COMMIT;
                "#,
            )?;
        }
        set_private_file_permissions(&self.database_path)?;
        Ok(())
    }

    fn cleanup_abandoned_files(&self) -> Result<(), RelayError> {
        let connection = self.connection()?;
        let mut statement = connection
            .prepare("SELECT stored_filename FROM attachments ORDER BY stored_filename")?;
        let referenced = statement
            .query_map([], |row| row.get::<_, String>(0))?
            .collect::<Result<HashSet<_>, _>>()?;
        for entry in fs::read_dir(&self.attachments_directory)? {
            let entry = entry?;
            let file_type = entry.file_type()?;
            if !file_type.is_file() {
                continue;
            }
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if name.starts_with(".pending-")
                || (name.ends_with(".bin") && !referenced.contains(name.as_ref()))
            {
                let _ = fs::remove_file(entry.path());
            }
        }
        Ok(())
    }

    fn connection(&self) -> Result<Connection, RelayError> {
        let connection = Connection::open(&self.database_path)?;
        connection.busy_timeout(Duration::from_secs(5))?;
        connection.pragma_update(None, "foreign_keys", true)?;
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.pragma_update(None, "synchronous", "FULL")?;
        connection.pragma_update(None, "trusted_schema", false)?;
        Ok(connection)
    }
}

impl AttachmentRecord {
    fn to_receipt(&self) -> AttachmentReceipt {
        AttachmentReceipt {
            attachment_id: self.attachment_id.clone(),
            ciphertext_sha256: self.ciphertext_sha256.clone(),
            ciphertext_size: self.ciphertext_size,
            remote_path: format!("/v1/attachments/{}", self.attachment_id),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyCheckInitialization {
    Created,
    AlreadyIdentical,
    AlreadyDifferent,
}

fn query_remote_item(
    connection: &Connection,
    item_id: &str,
) -> Result<Option<RemoteItem>, rusqlite::Error> {
    connection
        .query_row(
            r#"
            SELECT item_id, server_revision, version_token, deleted,
                   encrypted_payload, ciphertext_sha256
            FROM items
            WHERE item_id = ?1
            "#,
            [item_id],
            map_remote_item,
        )
        .optional()
}

fn map_remote_item(row: &rusqlite::Row<'_>) -> Result<RemoteItem, rusqlite::Error> {
    Ok(RemoteItem {
        item_id: row.get(0)?,
        server_revision: row.get(1)?,
        version_token: row.get(2)?,
        deleted: row.get(3)?,
        encrypted_payload: row.get(4)?,
        ciphertext_sha256: row.get(5)?,
    })
}

fn item_operation_exists(
    connection: &Connection,
    operation_id: &str,
) -> Result<bool, rusqlite::Error> {
    connection.query_row(
        "SELECT EXISTS(SELECT 1 FROM item_operations WHERE operation_id = ?1)",
        [operation_id],
        |row| row.get(0),
    )
}

fn query_item_operation(
    connection: &Connection,
    operation_id: &str,
    operation_kind: &str,
    item_id: &str,
    request_hash: &str,
) -> Result<Option<StoredOperation<StoredMutation>>, RelayError> {
    let row = connection
        .query_row(
            r#"
            SELECT operation_kind, item_id, request_sha256, status_code, response_json
            FROM item_operations
            WHERE operation_id = ?1
            "#,
            [operation_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, u16>(3)?,
                    row.get::<_, String>(4)?,
                ))
            },
        )
        .optional()?;
    let Some((stored_kind, stored_item, stored_hash, status_code, response_json)) = row else {
        return Ok(None);
    };
    if stored_kind != operation_kind || stored_item != item_id || stored_hash != request_hash {
        return Ok(Some(StoredOperation::IdempotencyMismatch));
    }
    Ok(Some(StoredOperation::Complete(StoredMutation {
        status_code,
        response: serde_json::from_str(&response_json)?,
    })))
}

fn insert_item_operation(
    transaction: &Transaction<'_>,
    operation_id: &str,
    operation_kind: &str,
    item_id: &str,
    request_hash: &str,
    stored: &StoredMutation,
) -> Result<(), RelayError> {
    transaction.execute(
        r#"
        INSERT INTO item_operations(
            operation_id, operation_kind, item_id, request_sha256,
            status_code, response_json
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        "#,
        params![
            operation_id,
            operation_kind,
            item_id,
            request_hash,
            stored.status_code,
            serde_json::to_string(&stored.response)?,
        ],
    )?;
    Ok(())
}

fn query_attachment(
    connection: &Connection,
    attachment_id: &str,
) -> Result<Option<AttachmentRecord>, rusqlite::Error> {
    connection
        .query_row(
            r#"
            SELECT attachment_id, ciphertext_sha256, ciphertext_size, stored_filename
            FROM attachments
            WHERE attachment_id = ?1
            "#,
            [attachment_id],
            |row| {
                let size = row.get::<_, i64>(2)?;
                let ciphertext_size = u64::try_from(size)
                    .map_err(|_| rusqlite::Error::IntegralValueOutOfRange(2, size))?;
                Ok(AttachmentRecord {
                    attachment_id: row.get(0)?,
                    ciphertext_sha256: row.get(1)?,
                    ciphertext_size,
                    stored_filename: row.get(3)?,
                })
            },
        )
        .optional()
}

fn query_attachment_operation(
    connection: &Connection,
    operation_id: &str,
    operation_kind: &str,
    attachment_id: &str,
) -> Result<Option<(String, String)>, RelayError> {
    let row = connection
        .query_row(
            r#"
            SELECT operation_kind, attachment_id, request_sha256, response_json
            FROM attachment_operations
            WHERE operation_id = ?1
            "#,
            [operation_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                ))
            },
        )
        .optional()?;
    let Some((stored_kind, stored_attachment, request_hash, response_json)) = row else {
        return Ok(None);
    };
    if stored_kind != operation_kind || stored_attachment != attachment_id {
        return Ok(Some((String::new(), response_json)));
    }
    Ok(Some((request_hash, response_json)))
}

fn insert_attachment_operation<T: serde::Serialize>(
    transaction: &Transaction<'_>,
    operation_id: &str,
    operation_kind: &str,
    attachment_id: &str,
    request_hash: &str,
    response: &T,
) -> Result<(), RelayError> {
    transaction.execute(
        r#"
        INSERT INTO attachment_operations(
            operation_id, operation_kind, attachment_id, request_sha256, response_json
        ) VALUES (?1, ?2, ?3, ?4, ?5)
        "#,
        params![
            operation_id,
            operation_kind,
            attachment_id,
            request_hash,
            serde_json::to_string(response)?,
        ],
    )?;
    Ok(())
}

fn attachment_filename(attachment_id: &str) -> String {
    format!("{}.bin", sha256_hex(attachment_id.as_bytes()))
}

#[cfg(unix)]
fn set_private_file_permissions(path: &Path) -> Result<(), RelayError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    Ok(())
}

#[cfg(not(unix))]
fn set_private_file_permissions(_: &Path) -> Result<(), RelayError> {
    Ok(())
}

#[cfg(unix)]
fn set_private_directory_permissions(path: &Path) -> Result<(), RelayError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

#[cfg(not(unix))]
fn set_private_directory_permissions(_: &Path) -> Result<(), RelayError> {
    Ok(())
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), RelayError> {
    File::open(path)?.sync_all()?;
    Ok(())
}

#[cfg(not(unix))]
fn sync_directory(_: &Path) -> Result<(), RelayError> {
    Ok(())
}
