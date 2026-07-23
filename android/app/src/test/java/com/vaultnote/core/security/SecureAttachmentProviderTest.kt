package com.vaultnote.core.security

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureAttachmentProviderTest {
    @Test
    fun `provider exposes correct metadata and repeatable seekable verified content`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = SecureAttachmentProvider()
        provider.attachInfo(
            context,
            ProviderInfo().apply {
                authority = "${context.packageName}.${SecureAttachmentUriFactory.AUTHORITY_SUFFIX}"
            },
        )
        provider.contentSourceOverride = FixtureContentSource()
        provider.availableSpaceBytes = { Long.MAX_VALUE }
        val uri = SecureAttachmentUriFactory(context).attachment(ATTACHMENT_ID, ACCESS_TOKEN)

        assertEquals("application/pdf", provider.getType(uri))
        provider.query(uri, null, null, null, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "contract.pdf",
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)),
            )
            assertEquals(
                PAYLOAD.size.toLong(),
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)),
            )
        }

        repeat(2) {
            provider.openFile(uri, "r").use { descriptor ->
                assertEquals(PAYLOAD.size.toLong(), descriptor.statSize)
                assertTrue(
                    File(context.cacheDir, "secure-handoff")
                        .listFiles()
                        .orEmpty()
                        .none { it.name.startsWith(".handoff-") },
                )
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readBytes()
                }
                assertTrue(bytes.contentEquals(PAYLOAD))
            }
        }
    }

    private class FixtureContentSource : SecureAttachmentContentSource {
        override suspend fun getMetadata(
            attachmentId: String,
            purpose: EncryptedFilePurpose,
            externalAccessToken: String?,
        ): RepositoryResult<SecureAttachmentMetadata> = RepositoryResult.Success(
            SecureAttachmentMetadata(
                displayName = "contract.pdf",
                mimeType = "application/pdf",
                sizeBytes = PAYLOAD.size.toLong(),
            ),
        )

        override suspend fun writeVerifiedContent(
            attachmentId: String,
            purpose: EncryptedFilePurpose,
            externalAccessToken: String?,
            output: OutputStream,
        ): RepositoryResult<Unit> {
            output.write(PAYLOAD)
            return RepositoryResult.Success(Unit)
        }
    }

    private companion object {
        const val ATTACHMENT_ID = "attachment_1"
        const val ACCESS_TOKEN = "valid_access_token_1234"
        val PAYLOAD = "%PDF-1.7\nverified".encodeToByteArray()
    }
}
