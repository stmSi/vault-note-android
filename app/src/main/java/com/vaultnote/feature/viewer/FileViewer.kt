package com.vaultnote.feature.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.security.ExternalAttachmentGrantRegistry
import com.vaultnote.core.security.SecureAttachmentUriFactory

sealed interface FileViewerResult {
    data object Opened : FileViewerResult
    data object NoCompatibleApp : FileViewerResult
    data object AccessDenied : FileViewerResult
    data object InvalidFile : FileViewerResult
}

interface FileViewer {
    fun open(activity: Activity, attachment: OpenableAttachment): FileViewerResult
}

/** Grants another app read access only to the exact attachment URI selected by the user. */
class AndroidFileViewer(
    private val uriFactory: SecureAttachmentUriFactory,
    private val externalGrants: ExternalAttachmentGrantRegistry,
) : FileViewer {
    override fun open(activity: Activity, attachment: OpenableAttachment): FileViewerResult {
        val uri = try {
            uriFactory.attachment(
                attachment.attachment.id,
                externalGrants.issue(attachment.attachment.id),
            )
        } catch (_: IllegalArgumentException) {
            return FileViewerResult.InvalidFile
        } catch (_: SecurityException) {
            return FileViewerResult.AccessDenied
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, attachment.attachment.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("VaultNote attachment", uri)
        return try {
            activity.startActivity(intent)
            FileViewerResult.Opened
        } catch (_: ActivityNotFoundException) {
            FileViewerResult.NoCompatibleApp
        } catch (_: SecurityException) {
            FileViewerResult.AccessDenied
        }
    }
}
