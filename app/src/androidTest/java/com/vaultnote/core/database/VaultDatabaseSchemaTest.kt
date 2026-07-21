package com.vaultnote.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDatabaseSchemaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = VaultDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun versionOneDataMigratesToCurrentVersion() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO vault_items (
                    id, type, title, body, ocr_text, is_pinned, is_favorite, is_archived,
                    created_at, updated_at, local_revision, remote_revision,
                    last_synced_revision, server_version_token, sync_status, deleted_at,
                    conflict_origin_id
                ) VALUES (
                    'item-1', 'NOTE', 'Schema fixture', 'Body', '', 0, 0, 0,
                    1, 9, 7, 5, 4, 'server-v5', 'PENDING', 11, NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO attachments (
                    id, parent_item_id, original_filename, mime_type, file_size,
                    sha256_checksum, local_encrypted_path, remote_path, thumbnail_path,
                    encryption_format_version, upload_status, created_at, ocr_state,
                    extracted_ocr_text, ocr_source_checksum, ocr_failure_code, ocr_updated_at
                ) VALUES (
                    'attachment-1', 'item-1', 'paper.pdf', 'application/pdf', 1234,
                    'fixture-checksum', 'attachments/attachment-1.bin', NULL, NULL,
                    1, 'PENDING', 2, 'NOT_APPLICABLE', '', NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO search_documents (
                    rowid, item_id, title, body, tags, attachment_filenames, ocr_text
                ) VALUES (
                    41, 'item-1', 'Schema fixture', 'Body', 'kept-tag', 'paper.pdf', ''
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO sync_operations (
                    operation_id, dedupe_key, item_id, attachment_id, operation_type,
                    target_revision, state, attempt_count, next_attempt_at, lease_token,
                    lease_expires_at, created_at, updated_at, last_error_code
                ) VALUES (
                    'operation-1', 'item:item-1', 'item-1', NULL, 'DELETE_ITEM',
                    7, 'PENDING', 0, 10, NULL, NULL, 10, 10, NULL
                )
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            VaultDatabase.SCHEMA_VERSION,
            true,
            *VaultDatabase.ALL_MIGRATIONS,
        ).use { db ->
            db.query(
                """
                SELECT original_filename, file_size, image_width, image_height, pdf_page_count
                FROM attachments WHERE id = 'attachment-1'
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("paper.pdf", cursor.getString(0))
                assertEquals(1234L, cursor.getLong(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals(true, cursor.isNull(3))
                assertEquals(true, cursor.isNull(4))
            }
            db.query(
                """
                SELECT title, color, local_revision, remote_revision, last_synced_revision,
                       server_version_token, deleted_at
                FROM vault_items WHERE id = 'item-1'
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Schema fixture", cursor.getString(0))
                assertEquals("DEFAULT", cursor.getString(1))
                assertEquals(7L, cursor.getLong(2))
                assertEquals(5L, cursor.getLong(3))
                assertEquals(4L, cursor.getLong(4))
                assertEquals("server-v5", cursor.getString(5))
                assertEquals(11L, cursor.getLong(6))
            }
            db.query(
                """
                SELECT tags, attachment_filenames FROM search_documents
                WHERE item_id = 'item-1'
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("kept-tag", cursor.getString(0))
                assertEquals("paper.pdf", cursor.getString(1))
            }
            db.query(
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name = 'index_attachments_created_at_id'
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query(
                """
                SELECT operation_type, target_revision FROM sync_operations
                WHERE dedupe_key = 'item:item-1'
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("DELETE_ITEM", cursor.getString(0))
                assertEquals(7L, cursor.getLong(1))
            }
            db.query("SELECT COUNT(*) FROM attachment_file_cleanup_journal").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "vaultnote-migration-test.db"
    }
}
