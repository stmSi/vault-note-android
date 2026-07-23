package com.vaultnote.core.repository

import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.common.model.VaultNote
import com.vaultnote.core.common.model.VaultTag
import kotlinx.coroutines.flow.Flow

interface VaultRepository {
    fun observeActiveItems(
        limit: Int = DEFAULT_ACTIVE_ITEM_LIMIT,
        offset: Int = 0,
    ): Flow<List<VaultItemSummary>>

    fun observeArchivedItems(
        limit: Int = DEFAULT_ACTIVE_ITEM_LIMIT,
        offset: Int = 0,
    ): Flow<List<VaultItemSummary>>

    fun observeTrashItems(
        limit: Int = DEFAULT_ACTIVE_ITEM_LIMIT,
        offset: Int = 0,
    ): Flow<List<VaultItemSummary>>

    fun observeNote(id: String): Flow<VaultNote?>

    fun observeTags(): Flow<List<VaultTag>>

    suspend fun createNote(title: String = "", body: String = ""): RepositoryResult<String>

    suspend fun createAttachmentContainer(
        title: String,
        type: VaultItemType,
    ): RepositoryResult<String>

    suspend fun saveNote(id: String, title: String, body: String): RepositoryResult<Unit>

    suspend fun saveNote(
        id: String,
        title: String,
        body: String,
        tagNames: Collection<String>,
    ): RepositoryResult<Unit>

    suspend fun setPinned(id: String, isPinned: Boolean): RepositoryResult<Unit>

    suspend fun reorderActiveItem(
        id: String,
        previousItemId: String?,
        nextItemId: String?,
    ): RepositoryResult<Unit>

    suspend fun setFavorite(id: String, isFavorite: Boolean): RepositoryResult<Unit>

    suspend fun setColor(id: String, color: VaultItemColor): RepositoryResult<Unit>

    suspend fun setArchived(id: String, isArchived: Boolean): RepositoryResult<Unit>

    suspend fun moveToTrash(id: String): RepositoryResult<Unit>

    suspend fun restore(id: String): RepositoryResult<Unit>

    suspend fun setTags(id: String, tagNames: Collection<String>): RepositoryResult<Unit>

    companion object {
        const val DEFAULT_ACTIVE_ITEM_LIMIT: Int = 100
        const val MAX_OBSERVED_ITEM_LIMIT: Int = 101
    }
}
