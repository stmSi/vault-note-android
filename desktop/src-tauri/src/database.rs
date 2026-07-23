use std::{
    fs::{self, File},
    io::Read,
    path::Path,
    sync::{Arc, Mutex},
    time::Duration,
};

use rusqlite::{Connection, OpenFlags, params};
use uuid::Uuid;

use crate::error::AppError;

const SCHEMA_VERSION: i64 = 5;
const SQLITE_HEADER: &[u8; 16] = b"SQLite format 3\0";

#[derive(Clone)]
pub struct Database {
    connection: Arc<Mutex<Connection>>,
}

impl Database {
    pub fn open(path: &Path, key: &[u8; 32]) -> Result<Self, AppError> {
        migrate_plaintext_database(path, key)?;
        let mut connection = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_FULL_MUTEX,
        )?;
        apply_key(&connection, key)?;
        verify_key(&connection)?;
        configure(&connection, true)?;
        migrate(&mut connection)?;
        Ok(Self {
            connection: Arc::new(Mutex::new(connection)),
        })
    }

    pub fn open_unencrypted(path: &Path) -> Result<Self, AppError> {
        if !can_initialize_vault(path)? {
            return Err(AppError::VaultConfiguration);
        }
        let mut connection = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_FULL_MUTEX,
        )?;
        verify_key(&connection)?;
        configure(&connection, true)?;
        migrate(&mut connection)?;
        Ok(Self {
            connection: Arc::new(Mutex::new(connection)),
        })
    }

    #[cfg(test)]
    pub fn open_in_memory() -> Result<Self, AppError> {
        let mut connection = Connection::open_in_memory()?;
        apply_key(&connection, &[0x5a; 32])?;
        verify_key(&connection)?;
        configure(&connection, false)?;
        migrate(&mut connection)?;
        Ok(Self {
            connection: Arc::new(Mutex::new(connection)),
        })
    }

    pub fn with_connection<T>(
        &self,
        operation: impl FnOnce(&mut Connection) -> Result<T, AppError>,
    ) -> Result<T, AppError> {
        let mut connection = self.connection.lock().map_err(|_| AppError::DatabaseLock)?;
        operation(&mut connection)
    }
}

/// Returns whether creating a new master key is safe for this database path.
///
/// A missing or readable plaintext database has no existing encryption key to
/// preserve. An encrypted, unreadable, or corrupt database must never receive a
/// replacement key automatically.
pub fn can_initialize_vault(path: &Path) -> Result<bool, AppError> {
    if !path.exists() || path.metadata()?.len() == 0 {
        return Ok(true);
    }
    if !has_plaintext_header(path)? {
        return Ok(false);
    }

    let connection = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_FULL_MUTEX,
    )?;
    connection.busy_timeout(Duration::from_secs(5))?;
    Ok(connection
        .query_row("SELECT count(*) FROM sqlite_master", [], |row| {
            row.get::<_, i64>(0)
        })
        .is_ok())
}

fn configure(connection: &Connection, file_backed: bool) -> Result<(), AppError> {
    connection.busy_timeout(Duration::from_secs(5))?;
    connection.pragma_update(None, "foreign_keys", "ON")?;
    connection.pragma_update(None, "trusted_schema", "OFF")?;
    connection.pragma_update(None, "synchronous", "FULL")?;
    if file_backed {
        connection.pragma_update(None, "journal_mode", "WAL")?;
    }
    Ok(())
}

fn apply_key(connection: &Connection, key: &[u8; 32]) -> Result<(), AppError> {
    let key_literal = raw_key_literal(key);
    connection.execute_batch(&format!("PRAGMA key = \"{key_literal}\";"))?;
    Ok(())
}

fn verify_key(connection: &Connection) -> Result<(), AppError> {
    connection.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))?;
    Ok(())
}

fn migrate(connection: &mut Connection) -> Result<(), AppError> {
    let version: i64 = connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
    if version > SCHEMA_VERSION {
        return Err(AppError::UnsupportedSchema);
    }
    if version == SCHEMA_VERSION {
        return Ok(());
    }

    let transaction = connection.transaction()?;
    if version == 0 {
        transaction.execute_batch(
            r#"
            CREATE TABLE vault_items (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL CHECK(type = 'NOTE'),
                color TEXT NOT NULL DEFAULT 'DEFAULT'
                    CHECK(color IN ('DEFAULT', 'RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE', 'PURPLE')),
                title TEXT NOT NULL CHECK(length(title) <= 500),
                body TEXT NOT NULL CHECK(length(body) <= 100000),
                ocr_text TEXT NOT NULL DEFAULT '',
                is_pinned INTEGER NOT NULL DEFAULT 0 CHECK(is_pinned IN (0, 1)),
                is_favorite INTEGER NOT NULL DEFAULT 0 CHECK(is_favorite IN (0, 1)),
                is_archived INTEGER NOT NULL DEFAULT 0 CHECK(is_archived IN (0, 1)),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                local_revision INTEGER NOT NULL CHECK(local_revision > 0),
                remote_revision INTEGER,
                last_synced_revision INTEGER,
                server_version_token TEXT,
                sync_status TEXT NOT NULL
                    CHECK(sync_status IN ('LOCAL_ONLY', 'PENDING', 'SYNCING', 'SYNCED', 'CONFLICT', 'FAILED')),
                deleted_at INTEGER,
                conflict_origin_id TEXT
            );

            CREATE INDEX index_vault_items_active_order
                ON vault_items(deleted_at, is_archived, is_pinned, updated_at, id);
            CREATE INDEX index_vault_items_sync_status ON vault_items(sync_status);

            CREATE VIRTUAL TABLE vault_items_fts USING fts5(
                item_id UNINDEXED,
                title,
                body,
                tokenize = 'unicode61 remove_diacritics 2'
            );

            CREATE TABLE sync_operations (
                operation_id TEXT NOT NULL PRIMARY KEY,
                dedupe_key TEXT NOT NULL UNIQUE,
                item_id TEXT NOT NULL REFERENCES vault_items(id) ON DELETE CASCADE,
                operation_type TEXT NOT NULL CHECK(operation_type IN ('UPSERT_ITEM', 'DELETE_ITEM')),
                target_revision INTEGER NOT NULL,
                state TEXT NOT NULL
                    CHECK(state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'FAILED_PERMANENT')),
                attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
                next_attempt_at INTEGER NOT NULL,
                lease_token TEXT,
                lease_expires_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_error_code TEXT
            );

            CREATE INDEX index_sync_operations_ready
                ON sync_operations(state, next_attempt_at, created_at);
            CREATE INDEX index_sync_operations_item_id ON sync_operations(item_id);
            "#,
        )?;
        transaction.pragma_update(None, "user_version", 1_i64)?;
    }
    if version < 2 {
        transaction.execute_batch(
            r#"
            CREATE TABLE app_security (
                singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                password_hash TEXT,
                lock_enabled INTEGER NOT NULL DEFAULT 0 CHECK(lock_enabled IN (0, 1))
            );
            INSERT INTO app_security(singleton_id, password_hash, lock_enabled)
            VALUES (1, NULL, 0);
            "#,
        )?;
    }
    if version < 3 {
        transaction.execute_batch(
            r#"
            CREATE TABLE attachments (
                id TEXT NOT NULL PRIMARY KEY,
                parent_item_id TEXT NOT NULL REFERENCES vault_items(id) ON DELETE CASCADE,
                display_name TEXT NOT NULL CHECK(length(display_name) BETWEEN 1 AND 255),
                mime_type TEXT NOT NULL,
                file_size INTEGER NOT NULL CHECK(file_size BETWEEN 0 AND 104857600),
                sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
                encrypted_relative_path TEXT NOT NULL UNIQUE,
                encryption_format_version INTEGER NOT NULL CHECK(encryption_format_version = 1),
                created_at INTEGER NOT NULL
            );
            CREATE INDEX index_attachments_parent ON attachments(parent_item_id, created_at, id);

            DROP TABLE vault_items_fts;
            CREATE VIRTUAL TABLE vault_items_fts USING fts5(
                item_id UNINDEXED,
                title,
                body,
                attachment_filenames,
                tokenize = 'unicode61 remove_diacritics 2'
            );
            INSERT INTO vault_items_fts(item_id, title, body, attachment_filenames)
            SELECT id, title, body, '' FROM vault_items;
            "#,
        )?;
    }
    if version < 4 {
        transaction.execute_batch(
            r#"
            CREATE TABLE tags (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
                normalized_name TEXT NOT NULL UNIQUE CHECK(length(normalized_name) BETWEEN 1 AND 100),
                created_at INTEGER NOT NULL
            );
            CREATE TABLE item_tags (
                item_id TEXT NOT NULL REFERENCES vault_items(id) ON DELETE CASCADE,
                tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                PRIMARY KEY(item_id, tag_id)
            );
            CREATE INDEX index_item_tags_tag_id ON item_tags(tag_id, item_id);
            ALTER TABLE attachments ADD COLUMN image_width INTEGER;
            ALTER TABLE attachments ADD COLUMN image_height INTEGER;
            ALTER TABLE attachments ADD COLUMN pdf_page_count INTEGER;
            ALTER TABLE attachments ADD COLUMN ocr_state TEXT NOT NULL DEFAULT 'NOT_APPLICABLE';
            ALTER TABLE attachments ADD COLUMN ocr_text TEXT NOT NULL DEFAULT '';
            ALTER TABLE attachments ADD COLUMN ocr_source_checksum TEXT;
            ALTER TABLE attachments ADD COLUMN ocr_failure_code TEXT;
            ALTER TABLE attachments ADD COLUMN ocr_updated_at INTEGER;
            "#,
        )?;
    }
    if version < 5 {
        transaction.execute_batch("DROP TABLE IF EXISTS app_security;")?;
    }
    transaction.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    transaction.commit()?;
    Ok(())
}

fn migrate_plaintext_database(path: &Path, key: &[u8; 32]) -> Result<(), AppError> {
    if !has_plaintext_header(path)? {
        return Ok(());
    }

    let parent = path.parent().ok_or_else(|| {
        AppError::Storage(std::io::Error::other("database directory unavailable"))
    })?;
    let migration_id = Uuid::new_v4().hyphenated().to_string();
    let encrypted_path = parent.join(format!(".vaultnote-encrypted-{migration_id}.tmp"));
    let backup_path = parent.join(format!(".vaultnote-plaintext-{migration_id}.bak"));
    let result = export_encrypted_copy(path, &encrypted_path, key)
        .and_then(|()| replace_database_atomically(path, &encrypted_path, &backup_path));
    if result.is_err() {
        let _ = fs::remove_file(&encrypted_path);
    }
    result
}

fn export_encrypted_copy(
    source: &Path,
    destination: &Path,
    key: &[u8; 32],
) -> Result<(), AppError> {
    File::create(destination)?.sync_all()?;
    let connection = Connection::open_with_flags(
        source,
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_FULL_MUTEX,
    )?;
    connection.busy_timeout(Duration::from_secs(5))?;
    connection.pragma_update(None, "wal_checkpoint", "TRUNCATE")?;
    connection.pragma_update(None, "journal_mode", "DELETE")?;
    let version: i64 = connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
    let destination_text = destination.to_string_lossy();
    let key_literal = raw_key_literal(key);
    connection.execute(
        "ATTACH DATABASE ?1 AS encrypted KEY ?2",
        params![destination_text.as_ref(), key_literal],
    )?;
    let export_result: Result<(), AppError> = (|| {
        connection.query_row("SELECT sqlcipher_export('encrypted')", [], |_| Ok(()))?;
        connection.execute_batch(&format!("PRAGMA encrypted.user_version = {version};"))?;
        Ok(())
    })();
    let detach_result = connection.execute_batch("DETACH DATABASE encrypted");
    export_result?;
    detach_result?;
    drop(connection);
    File::options()
        .read(true)
        .write(true)
        .open(destination)?
        .sync_all()?;
    Ok(())
}

fn replace_database_atomically(
    destination: &Path,
    encrypted_path: &Path,
    backup_path: &Path,
) -> Result<(), AppError> {
    fs::rename(destination, backup_path)?;
    if let Err(error) = fs::rename(encrypted_path, destination) {
        let _ = fs::rename(backup_path, destination);
        return Err(AppError::Storage(error));
    }
    sync_directory(destination.parent())?;
    fs::remove_file(backup_path)?;
    sync_directory(destination.parent())?;
    Ok(())
}

fn has_plaintext_header(path: &Path) -> Result<bool, AppError> {
    if !path.exists() || path.metadata()?.len() < SQLITE_HEADER.len() as u64 {
        return Ok(false);
    }
    let mut header = [0_u8; 16];
    File::open(path)?.read_exact(&mut header)?;
    Ok(&header == SQLITE_HEADER)
}

fn raw_key_literal(key: &[u8; 32]) -> String {
    let mut literal = String::with_capacity(67);
    literal.push_str("x'");
    for byte in key {
        use std::fmt::Write as _;
        write!(literal, "{byte:02x}").expect("writing to a string cannot fail");
    }
    literal.push('\'');
    literal
}

#[cfg(unix)]
fn sync_directory(directory: Option<&Path>) -> Result<(), AppError> {
    if let Some(directory) = directory {
        File::open(directory)?.sync_all()?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn sync_directory(_directory: Option<&Path>) -> Result<(), AppError> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn migration_creates_fts5_schema_and_sets_version() {
        let database = Database::open_in_memory().expect("database should open");
        database
            .with_connection(|connection| {
                let version: i64 =
                    connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
                let fts_sql: String = connection.query_row(
                    "SELECT sql FROM sqlite_master WHERE name = 'vault_items_fts'",
                    [],
                    |row| row.get(0),
                )?;
                let legacy_security_tables: i64 = connection.query_row(
                    "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'app_security'",
                    [],
                    |row| row.get(0),
                )?;
                assert_eq!(version, SCHEMA_VERSION);
                assert!(fts_sql.contains("fts5"));
                assert_eq!(legacy_security_tables, 0);
                Ok(())
            })
            .expect("schema should be readable");
    }

    #[test]
    fn plaintext_database_is_atomically_migrated_to_sqlcipher() {
        let directory = tempdir().expect("temporary directory should exist");
        let path = directory.path().join("vaultnote.db");
        {
            let connection = Connection::open(&path).expect("plaintext database should open");
            connection
                .execute_batch(
                    "CREATE TABLE legacy(value TEXT); INSERT INTO legacy VALUES ('kept');",
                )
                .expect("plaintext data should be created");
        }
        assert!(has_plaintext_header(&path).expect("header should be readable"));

        let database = Database::open(&path, &[0x5a; 32]).expect("migration should succeed");
        database
            .with_connection(|connection| {
                let value: String =
                    connection.query_row("SELECT value FROM legacy", [], |row| row.get(0))?;
                assert_eq!(value, "kept");
                Ok(())
            })
            .expect("migrated content should remain readable");
        assert!(!has_plaintext_header(&path).expect("header should be readable"));
    }

    #[test]
    fn master_key_initialization_is_allowed_only_for_new_or_plaintext_databases() {
        let directory = tempdir().expect("temporary directory should exist");
        let missing_path = directory.path().join("missing.db");
        assert!(can_initialize_vault(&missing_path).expect("missing path should be inspected"));

        let plaintext_path = directory.path().join("plaintext.db");
        let plaintext = Connection::open(&plaintext_path).expect("plaintext database should open");
        plaintext
            .execute_batch("CREATE TABLE legacy(value TEXT); INSERT INTO legacy VALUES ('kept');")
            .expect("plaintext data should be created");
        assert!(
            can_initialize_vault(&plaintext_path).expect("plaintext database should be inspected")
        );

        let encrypted_path = directory.path().join("encrypted.db");
        let encrypted = Database::open(&encrypted_path, &[0x5a; 32])
            .expect("encrypted database should be created");
        encrypted
            .with_connection(|connection| {
                connection.execute(
                    "INSERT INTO vault_items(
                        id, type, color, title, body, ocr_text, is_pinned, is_favorite,
                        is_archived, created_at, updated_at, local_revision, sync_status
                    ) VALUES (?1, 'NOTE', 'DEFAULT', '', '', '', 0, 0, 0, 1, 1, 1, 'LOCAL_ONLY')",
                    [Uuid::new_v4().hyphenated().to_string()],
                )?;
                Ok(())
            })
            .expect("encrypted data should be written");
        assert!(
            !can_initialize_vault(&encrypted_path).expect("encrypted database should be inspected")
        );
    }

    #[test]
    fn unencrypted_database_remains_plaintext() {
        let directory = tempdir().expect("temporary directory should exist");
        let path = directory.path().join("vaultnote.db");
        let database = Database::open_unencrypted(&path).expect("database should open");
        database
            .with_connection(|connection| {
                connection.execute(
                    "INSERT INTO vault_items(
                        id, type, color, title, body, ocr_text, is_pinned, is_favorite,
                        is_archived, created_at, updated_at, local_revision, sync_status
                    ) VALUES (?1, 'NOTE', 'DEFAULT', '', '', '', 0, 0, 0, 1, 1, 1, 'LOCAL_ONLY')",
                    [Uuid::new_v4().hyphenated().to_string()],
                )?;
                connection.pragma_update(None, "wal_checkpoint", "TRUNCATE")?;
                Ok(())
            })
            .expect("plaintext data should be written");
        assert!(has_plaintext_header(&path).expect("header should be readable"));
    }
}
