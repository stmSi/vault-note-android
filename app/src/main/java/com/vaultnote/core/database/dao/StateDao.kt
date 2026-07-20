package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vaultnote.core.database.entity.AppSettingEntity
import com.vaultnote.core.database.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(state: SyncStateEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(state: SyncStateEntity): Int

    @Query("SELECT * FROM sync_state WHERE scope = :scope LIMIT 1")
    suspend fun get(scope: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE scope = :scope LIMIT 1")
    fun observe(scope: String): Flow<SyncStateEntity?>
}

@Dao
interface AppSettingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(setting: AppSettingEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(setting: AppSettingEntity): Int

    @Query("SELECT * FROM app_settings WHERE setting_key = :key LIMIT 1")
    suspend fun get(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings WHERE setting_key = :key LIMIT 1")
    fun observe(key: String): Flow<AppSettingEntity?>

    @Query("DELETE FROM app_settings WHERE setting_key = :key")
    suspend fun delete(key: String): Int
}
