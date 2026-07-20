package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "setting_key")
    val key: String,
    @ColumnInfo(name = "setting_value")
    val value: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
