package com.vaultnote.core.files

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamingFileCopierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `copies once while producing exact byte count and SHA-256`() = runTest {
        val directory = temporaryFolder.newFolder("attachments")
        val destination = directory.resolve("pending.tmp")
        val copier = StreamingFileCopier(AvailableSpaceProvider { Long.MAX_VALUE })

        val result = copier.copy(
            input = ByteArrayInputStream("abc".toByteArray()),
            temporaryFile = destination,
            storageDirectory = directory,
            declaredSize = 3,
        )

        val copied = (result as RepositoryResult.Success).value
        assertEquals(3L, copied.byteCount)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", copied.sha256)
        assertEquals("abc", destination.readText())
    }

    @Test
    fun `enforces actual streaming limit and removes partial output`() = runTest {
        val directory = temporaryFolder.newFolder("limited")
        val destination = directory.resolve("pending.tmp")
        val copier = StreamingFileCopier(
            availableSpaceProvider = AvailableSpaceProvider { Long.MAX_VALUE },
            maximumBytes = 8,
            freeSpaceReserveBytes = 0,
        )

        val result = copier.copy(
            input = ByteArrayInputStream(ByteArray(9) { it.toByte() }),
            temporaryFile = destination,
            storageDirectory = directory,
            declaredSize = null,
        )

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.FileTooLarge(8), (result as RepositoryResult.Failure).error)
        assertFalse(destination.exists())
    }

    @Test
    fun `fails before reading when declared size exceeds production limit`() = runTest {
        val directory = temporaryFolder.newFolder("declared")
        val destination = directory.resolve("pending.tmp")
        val copier = StreamingFileCopier(AvailableSpaceProvider { Long.MAX_VALUE })

        val result = copier.copy(
            input = ByteArrayInputStream(ByteArray(0)),
            temporaryFile = destination,
            storageDirectory = directory,
            declaredSize = MAX_ATTACHMENT_BYTES + 1,
        )

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(
            AppError.FileTooLarge(MAX_ATTACHMENT_BYTES),
            (result as RepositoryResult.Failure).error,
        )
        assertFalse(destination.exists())
    }
}
