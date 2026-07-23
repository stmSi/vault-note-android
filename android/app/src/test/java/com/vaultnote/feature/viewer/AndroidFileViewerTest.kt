package com.vaultnote.feature.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.core.security.ElapsedRealtimeProvider
import com.vaultnote.core.security.ExternalAttachmentGrantRegistry
import com.vaultnote.core.security.SecureAttachmentUriFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidFileViewerTest {
    @Test
    fun `open uses validated MIME and a bounded read grant`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val grants = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val viewer = AndroidFileViewer(SecureAttachmentUriFactory(activity), grants)

        assertEquals(FileViewerResult.Opened, viewer.open(activity, attachment()))

        val intent = shadowOf(activity).nextStartedActivity
        val uri = requireNotNull(intent.data)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(
            grants.validate(
                ATTACHMENT_ID,
                uri.getQueryParameter(SecureAttachmentUriFactory.ACCESS_TOKEN_PARAMETER),
            ),
        )
    }

    @Test
    fun `share uses a MIME typed stream with a bounded read grant`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val grants = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val viewer = AndroidFileViewer(SecureAttachmentUriFactory(activity), grants)

        assertEquals(FileViewerResult.Opened, viewer.share(activity, attachment()))

        val chooser = shadowOf(activity).nextStartedActivity
        @Suppress("DEPRECATION")
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        @Suppress("DEPRECATION")
        val stream = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
        assertTrue(
            grants.validate(
                ATTACHMENT_ID,
                stream.getQueryParameter(SecureAttachmentUriFactory.ACCESS_TOKEN_PARAMETER),
            ),
        )
    }

    private fun attachment(): OpenableAttachment = OpenableAttachment(
        attachment = VaultAttachment(
            id = ATTACHMENT_ID,
            parentItemId = "item_1",
            displayName = "contract.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = 20L,
            imageWidth = null,
            imageHeight = null,
            pdfPageCount = 1,
            sha256Checksum = "checksum",
            remotePath = null,
            thumbnailUri = null,
            encryptionFormatVersion = 1,
            uploadStatus = AttachmentUploadStatus.PENDING,
            createdAtEpochMillis = 1L,
            ocrState = OcrState.PENDING,
            ocrFailureCode = null,
        ),
        contentUri = Uri.EMPTY,
    )

    private companion object {
        const val ATTACHMENT_ID = "attachment_1"
    }
}
