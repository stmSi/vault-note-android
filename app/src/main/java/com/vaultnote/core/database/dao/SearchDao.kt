package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vaultnote.core.database.entity.SearchDocumentEntity

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocument(document: SearchDocumentEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateDocument(document: SearchDocumentEntity): Int

    @Query("SELECT * FROM search_documents WHERE item_id = :itemId LIMIT 1")
    suspend fun getDocumentForItem(itemId: String): SearchDocumentEntity?

    @Query("DELETE FROM search_documents WHERE item_id = :itemId")
    suspend fun deleteDocumentForItem(itemId: String): Int
}
