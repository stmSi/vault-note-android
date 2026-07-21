package com.vaultnote.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vaultnote.core.database.dao.AppSettingDao
import com.vaultnote.core.database.dao.AttachmentDao
import com.vaultnote.core.database.dao.AttachmentFileCleanupDao
import com.vaultnote.core.database.dao.SearchDao
import com.vaultnote.core.database.dao.SyncOperationDao
import com.vaultnote.core.database.dao.SyncStateDao
import com.vaultnote.core.database.dao.TagDao
import com.vaultnote.core.database.dao.VaultItemDao
import com.vaultnote.core.database.entity.AppSettingEntity
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.database.entity.AttachmentFileCleanupEntity
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.SearchFtsEntity
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.database.entity.SyncStateEntity
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity

@Database(
    entities = [
        VaultItemEntity::class,
        AttachmentEntity::class,
        AttachmentFileCleanupEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        SyncOperationEntity::class,
        SyncStateEntity::class,
        SearchDocumentEntity::class,
        SearchFtsEntity::class,
        AppSettingEntity::class,
    ],
    version = VaultDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(RoomTypeConverters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultItemDao(): VaultItemDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun attachmentFileCleanupDao(): AttachmentFileCleanupDao
    abstract fun tagDao(): TagDao
    abstract fun syncOperationDao(): SyncOperationDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun searchDao(): SearchDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        const val SCHEMA_VERSION: Int = 3
        const val DATABASE_NAME: String = "vaultnote.db"

        /** Adds optional media metadata and a crash-safe attachment file cleanup journal. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN image_width INTEGER")
                db.execSQL("ALTER TABLE attachments ADD COLUMN image_height INTEGER")
                db.execSQL("ALTER TABLE attachments ADD COLUMN pdf_page_count INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachment_file_cleanup_journal (
                        cleanup_id TEXT NOT NULL,
                        local_relative_path TEXT NOT NULL,
                        thumbnail_relative_path TEXT,
                        created_at INTEGER NOT NULL,
                        attempt_count INTEGER NOT NULL,
                        last_attempt_at INTEGER,
                        PRIMARY KEY(cleanup_id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_attachment_file_cleanup_journal_created_at
                    ON attachment_file_cleanup_journal(created_at)
                    """.trimIndent(),
                )
            }
        }

        /** Adds a stable, user-selected color while preserving existing items as neutral. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE vault_items ADD COLUMN color TEXT NOT NULL DEFAULT 'DEFAULT'",
                )
            }
        }

        /** Production callers intentionally have no destructive migration fallback. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun create(context: Context): VaultDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VaultDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(*ALL_MIGRATIONS).build()
    }
}
