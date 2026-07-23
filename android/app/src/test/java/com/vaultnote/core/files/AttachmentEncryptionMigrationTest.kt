package com.vaultnote.core.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import com.vaultnote.core.encryption.EncryptionContext
import com.vaultnote.core.encryption.EncryptionEnvelopeInfo
import com.vaultnote.core.encryption.EncryptionService
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentEncryptionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val encryption = RecordingEncryptionService()
    private val manager = AndroidAttachmentFileManager(
        context = context,
        dispatchers = TestDispatchers,
        encryptionService = encryption,
    )

    @After
    fun cleanUp() {
        File(context.filesDir, "vault").deleteRecursively()
    }

    @Test
    fun `legacy file is encrypted only after its persisted checksum matches`() = runBlocking {
        val relativePath = manager.planAttachmentPaths("attachment_1")
            .successValue()
            .localRelativePath
        val file = manager.resolveAttachmentPath(relativePath).successValue()
        file.parentFile?.mkdirs()
        file.writeText("PDF bytes")

        val result = manager.upgradeStoredEncryption(
            attachmentId = "attachment_1",
            localRelativePath = relativePath,
            thumbnailRelativePath = null,
            storedFormatVersion = 0,
            expectedPlaintextSha256 = CHECKSUM,
        )

        assertEquals(CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION, result.successValue())
        assertEquals(1, encryption.encryptCalls)
    }

    @Test
    fun `legacy checksum mismatch is rejected before encryption`() = runBlocking {
        val relativePath = manager.planAttachmentPaths("attachment_2")
            .successValue()
            .localRelativePath
        val file = manager.resolveAttachmentPath(relativePath).successValue()
        file.parentFile?.mkdirs()
        file.writeText("tampered bytes")

        val result = manager.upgradeStoredEncryption(
            attachmentId = "attachment_2",
            localRelativePath = relativePath,
            thumbnailRelativePath = null,
            storedFormatVersion = 0,
            expectedPlaintextSha256 = CHECKSUM,
        )

        assertTrue(result is RepositoryResult.Failure)
        assertTrue((result as RepositoryResult.Failure).error is AppError.CorruptedFile)
        assertEquals(0, encryption.encryptCalls)
    }

    private class RecordingEncryptionService : EncryptionService {
        var encryptCalls = 0
            private set

        override suspend fun encryptFileAtomically(
            plaintext: File,
            destination: File,
            context: EncryptionContext,
            replaceExisting: Boolean,
        ): RepositoryResult<EncryptionEnvelopeInfo> {
            encryptCalls += 1
            return RepositoryResult.Success(
                EncryptionEnvelopeInfo(
                    formatVersion = CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                    keyVersion = 1,
                    plaintextLength = plaintext.length(),
                ),
            )
        }

        override suspend fun inspectAndVerify(
            encryptedFile: File,
            context: EncryptionContext,
        ): RepositoryResult<EncryptionEnvelopeInfo> = error("Not used for legacy plaintext")

        override suspend fun decryptVerifiedTo(
            encryptedFile: File,
            context: EncryptionContext,
            output: OutputStream,
        ): RepositoryResult<EncryptionEnvelopeInfo> = error("Not used for migration")

        override suspend fun hasEnvelope(file: File): RepositoryResult<Boolean> =
            RepositoryResult.Success(false)
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun <T> RepositoryResult<T>.successValue(): T {
        assertFalse(this is RepositoryResult.Failure)
        return (this as RepositoryResult.Success<T>).value
    }

    private companion object {
        const val CHECKSUM =
            "662f0631667382600d18269aeb84b04987b60124d1371b34cd783ae06cbe656c"
    }
}
