package com.vaultnote.app

import com.vaultnote.feature.importing.IncomingImport

interface MainNavigator {
    fun openNoteEditor(itemId: String)

    fun openImportPreview(
        parentItemId: String?,
        incomingImport: IncomingImport,
        cameraCaptureId: String? = null,
        standaloneFiles: Boolean = false,
    ): Boolean

    fun takePendingImport(token: Long): IncomingImport?

    fun completeImport(itemId: String, openCreatedItem: Boolean)

    fun openAttachment(attachmentId: String)

    fun openSecuritySettings()

    fun openSearch()

    fun openSyncStatus()

    fun openConflicts()

    fun openBackupExport()

    fun openBackupRestore()

    /** Starts a bounded user-initiated external handoff. Returns false when already locked. */
    fun beginSecureExternalHandoff(): Boolean

    fun endSecureExternalHandoff()

    fun navigateBack()
}
