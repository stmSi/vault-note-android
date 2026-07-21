package com.vaultnote.app

import com.vaultnote.feature.importing.IncomingImport

interface MainNavigator {
    fun openNoteEditor(itemId: String)

    fun openImportPreview(
        parentItemId: String?,
        incomingImport: IncomingImport,
        cameraCaptureId: String? = null,
    ): Boolean

    fun takePendingImport(token: Long): IncomingImport?

    fun completeImport(itemId: String, createdItem: Boolean)

    fun openAttachment(attachmentId: String)

    fun openSecuritySettings()

    fun navigateBack()
}
