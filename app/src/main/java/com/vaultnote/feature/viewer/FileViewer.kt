package com.vaultnote.feature.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import com.vaultnote.R
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
    fun share(activity: Activity, attachment: OpenableAttachment): FileViewerResult
}

/** Grants another app read access only to the exact attachment URI selected by the user. */
class AndroidFileViewer(
    private val uriFactory: SecureAttachmentUriFactory,
    private val externalGrants: ExternalAttachmentGrantRegistry,
) : FileViewer {
    override fun open(activity: Activity, attachment: OpenableAttachment): FileViewerResult {
        val handoff = when (val prepared = secureHandoff(attachment)) {
            is HandoffPreparation.Ready -> prepared.handoff
            is HandoffPreparation.Failed -> return prepared.result
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(handoff.uri, attachment.attachment.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("VaultNote attachment", handoff.uri)
        return launch(activity, intent, handoff)
    }

    override fun share(activity: Activity, attachment: OpenableAttachment): FileViewerResult {
        val handoff = when (val prepared = secureHandoff(attachment)) {
            is HandoffPreparation.Ready -> prepared.handoff
            is HandoffPreparation.Failed -> return prepared.result
        }
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType(attachment.attachment.mimeType)
            .putExtra(Intent.EXTRA_STREAM, handoff.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        sendIntent.clipData = ClipData.newRawUri("VaultNote attachment", handoff.uri)
        val chooser = Intent.createChooser(sendIntent, activity.getString(R.string.share_attachment))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(activity, chooser, handoff)
    }

    private fun secureHandoff(attachment: OpenableAttachment): HandoffPreparation = try {
        val attachmentId = attachment.attachment.id
        val token = externalGrants.issue(attachmentId)
        var prepared = false
        try {
            HandoffPreparation.Ready(
                Handoff(
                    uri = uriFactory.attachment(attachmentId, token),
                    token = token,
                ),
            ).also { prepared = true }
        } finally {
            if (!prepared) externalGrants.revoke(token)
        }
    } catch (_: IllegalArgumentException) {
        HandoffPreparation.Failed(FileViewerResult.InvalidFile)
    } catch (_: SecurityException) {
        HandoffPreparation.Failed(FileViewerResult.AccessDenied)
    }

    private fun launch(
        activity: Activity,
        intent: Intent,
        handoff: Handoff,
    ): FileViewerResult {
        return try {
            activity.startActivity(intent)
            FileViewerResult.Opened
        } catch (_: ActivityNotFoundException) {
            externalGrants.revoke(handoff.token)
            FileViewerResult.NoCompatibleApp
        } catch (_: SecurityException) {
            externalGrants.revoke(handoff.token)
            FileViewerResult.AccessDenied
        }
    }

    private data class Handoff(val uri: android.net.Uri, val token: String)

    private sealed interface HandoffPreparation {
        data class Ready(val handoff: Handoff) : HandoffPreparation
        data class Failed(val result: FileViewerResult) : HandoffPreparation
    }
}
