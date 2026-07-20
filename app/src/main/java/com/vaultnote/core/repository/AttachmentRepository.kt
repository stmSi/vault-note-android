package com.vaultnote.core.repository

import android.net.Uri
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.AttachmentImportResult
import com.vaultnote.core.common.model.AttachmentDeleteResult
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.VaultAttachment
import kotlinx.coroutines.flow.Flow

interface AttachmentRepository {
    fun observeForItem(itemId: String): Flow<List<VaultAttachment>>

    suspend fun importFromUri(
        parentItemId: String,
        sourceUri: Uri,
    ): RepositoryResult<AttachmentImportResult>

    suspend fun getById(attachmentId: String): RepositoryResult<VaultAttachment>

    suspend fun getOpenableAttachment(
        attachmentId: String,
    ): RepositoryResult<OpenableAttachment>

    suspend fun delete(attachmentId: String): RepositoryResult<AttachmentDeleteResult>

    suspend fun reconcileFileCleanup(): RepositoryResult<Unit>
}
