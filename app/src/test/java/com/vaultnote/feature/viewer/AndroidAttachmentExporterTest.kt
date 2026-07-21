package com.vaultnote.feature.viewer

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import com.vaultnote.core.security.ElapsedRealtimeProvider
import com.vaultnote.core.security.ExternalAttachmentGrantRegistry
import com.vaultnote.core.security.SecureAttachmentContentSource
import com.vaultnote.core.security.SecureAttachmentMetadata
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAttachmentExporterTest {
    @Test
    fun `save streams verified content and revokes its temporary authorization`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = WritableTestProvider()
        provider.attachInfo(
            context,
            ProviderInfo().apply { authority = DESTINATION_AUTHORITY },
        )
        val grants = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val contentSource = RecordingContentSource(PAYLOAD)
        val exporter = AndroidAttachmentExporter(
            contentResolver = ContentResolver.wrap(provider),
            contentSource = contentSource,
            externalGrants = grants,
            dispatchers = TestDispatchers,
        )
        val prepared = (exporter.prepare(ATTACHMENT_ID) as RepositoryResult.Success).value

        val result = exporter.save(
            prepared,
            Uri.parse("content://$DESTINATION_AUTHORITY/exported.pdf"),
        )

        assertTrue(result is RepositoryResult.Success)
        assertTrue(provider.outputFile.readBytes().contentEquals(PAYLOAD))
        assertEquals(prepared.accessToken, contentSource.receivedToken)
        assertFalse(grants.validate(ATTACHMENT_ID, prepared.accessToken))
        assertFalse(prepared.toString().contains(prepared.accessToken))
    }

    @Test
    fun `failed save removes plaintext when destination provider cannot delete`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = WritableTestProvider(canDelete = false)
        provider.attachInfo(
            context,
            ProviderInfo().apply { authority = DESTINATION_AUTHORITY },
        )
        val grants = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val exporter = AndroidAttachmentExporter(
            contentResolver = ContentResolver.wrap(provider),
            contentSource = FailingContentSource,
            externalGrants = grants,
            dispatchers = TestDispatchers,
        )
        val prepared = (exporter.prepare(ATTACHMENT_ID) as RepositoryResult.Success).value

        val result = exporter.save(
            prepared,
            Uri.parse("content://$DESTINATION_AUTHORITY/partial.pdf"),
        )

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(0L, provider.outputFile.length())
        assertFalse(grants.validate(ATTACHMENT_ID, prepared.accessToken))
    }

    private class RecordingContentSource(
        private val payload: ByteArray,
    ) : SecureAttachmentContentSource {
        var receivedToken: String? = null

        override suspend fun getMetadata(
            attachmentId: String,
            purpose: EncryptedFilePurpose,
            externalAccessToken: String?,
        ): RepositoryResult<SecureAttachmentMetadata> = RepositoryResult.Success(
            SecureAttachmentMetadata("exported.pdf", "application/pdf", payload.size.toLong()),
        )

        override suspend fun writeVerifiedContent(
            attachmentId: String,
            purpose: EncryptedFilePurpose,
            externalAccessToken: String?,
            output: OutputStream,
        ): RepositoryResult<Unit> {
            receivedToken = externalAccessToken
            output.write(payload)
            return RepositoryResult.Success(Unit)
        }
    }

    private object FailingContentSource : SecureAttachmentContentSource {
        override suspend fun getMetadata(
            attachmentId: String,
            purpose: EncryptedFilePurpose,
            externalAccessToken: String?,
        ): RepositoryResult<SecureAttachmentMetadata> = RepositoryResult.Success(
            SecureAttachmentMetadata("partial.pdf", "application/pdf", PAYLOAD.size.toLong()),
        )

        override suspend fun writeVerifiedContent(
            attachmentId: String,
            purpose: EncryptedFilePurpose,
            externalAccessToken: String?,
            output: OutputStream,
        ): RepositoryResult<Unit> {
            output.write(PAYLOAD)
            return RepositoryResult.Failure(AppError.CorruptedFile)
        }
    }

    private class WritableTestProvider(
        private val canDelete: Boolean = true,
    ) : ContentProvider() {
        lateinit var outputFile: File

        override fun onCreate(): Boolean {
            outputFile = File(requireNotNull(context).cacheDir, "attachment-export-test.bin")
            outputFile.delete()
            return true
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                outputFile,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY,
            )

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            if (canDelete && outputFile.delete()) 1 else 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        const val DESTINATION_AUTHORITY = "com.vaultnote.test.destination"
        const val ATTACHMENT_ID = "attachment_1"
        val PAYLOAD = "%PDF-1.7\nfixture".encodeToByteArray()
    }
}
