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

    fun openSearch()

    fun openSyncStatus()

    fun openConflicts()

    fun openBackupExport()

    fun openBackupRestore()

    /**
     * Starts a bounded handoff to Android's document picker without treating the picker as an
     * ordinary background transition. Returns false when sensitive content is already locked.
     */
    fun beginSecureDocumentPicker(): Boolean

    fun endSecureDocumentPicker()

    fun navigateBack()
}
