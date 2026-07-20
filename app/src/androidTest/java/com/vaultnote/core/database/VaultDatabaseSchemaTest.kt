package com.vaultnote.core.database

import android.content.Context
import androidx.room.Room
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
    fun versionOneExportCreatesAndValidatesCurrentSchema() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, VaultDatabase.SCHEMA_VERSION).use { db ->
            db.execSQL(
                """
                INSERT INTO vault_items (
                    id, type, title, body, ocr_text, is_pinned, is_favorite, is_archived,
                    created_at, updated_at, local_revision, remote_revision,
                    last_synced_revision, server_version_token, sync_status, deleted_at,
                    conflict_origin_id
                ) VALUES (
                    'item-1', 'NOTE', 'Schema fixture', 'Body', '', 0, 0, 0,
                    1, 1, 1, NULL, NULL, NULL, 'PENDING', NULL, NULL
                )
                """.trimIndent(),
            )
        }

        val database = Room.databaseBuilder(context, VaultDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*VaultDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT title FROM vault_items WHERE id = 'item-1'",
            )
            cursor.use {
                assertEquals(true, it.moveToFirst())
                assertEquals("Schema fixture", it.getString(0))
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "vaultnote-migration-test.db"
    }
}
