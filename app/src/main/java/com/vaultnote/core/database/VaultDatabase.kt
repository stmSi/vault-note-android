package com.vaultnote.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.vaultnote.core.database.dao.AppSettingDao
import com.vaultnote.core.database.dao.AttachmentDao
import com.vaultnote.core.database.dao.SearchDao
import com.vaultnote.core.database.dao.SyncOperationDao
import com.vaultnote.core.database.dao.SyncStateDao
import com.vaultnote.core.database.dao.TagDao
import com.vaultnote.core.database.dao.VaultItemDao
import com.vaultnote.core.database.entity.AppSettingEntity
import com.vaultnote.core.database.entity.AttachmentEntity
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
    abstract fun tagDao(): TagDao
    abstract fun syncOperationDao(): SyncOperationDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun searchDao(): SearchDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val DATABASE_NAME: String = "vaultnote.db"

        /**
         * Version one is the initial schema. Every subsequent schema version must add an explicit
         * Migration here; production callers intentionally have no destructive fallback.
         */
        val ALL_MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context): VaultDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VaultDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(*ALL_MIGRATIONS).build()
    }
}
