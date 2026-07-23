package com.vaultnote.core.files

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentFilenamePolicyTest {
    @Test
    fun `rename without extension preserves validated format extension`() {
        val result = AttachmentFilenamePolicy.rename(
            requestedName = "Bangkok receipt",
            currentName = "IMG_2048.JPEG",
            format = AttachmentFormat.JPEG,
        )

        assertEquals("Bangkok receipt.jpeg", result.successValue())
    }

    @Test
    fun `rename accepts an alternate extension for the same format`() {
        val result = AttachmentFilenamePolicy.rename(
            requestedName = "Bangkok receipt.jpg",
            currentName = "IMG_2048.jpeg",
            format = AttachmentFormat.JPEG,
        )

        assertEquals("Bangkok receipt.jpg", result.successValue())
    }

    @Test
    fun `rename rejects misleading extension and unsafe paths`() {
        listOf("receipt.exe", "../receipt.pdf").forEach { requestedName ->
            val result = AttachmentFilenamePolicy.rename(
                requestedName = requestedName,
                currentName = "receipt.pdf",
                format = AttachmentFormat.PDF,
            )

            assertTrue(result is RepositoryResult.Failure)
            assertTrue((result as RepositoryResult.Failure).error is AppError.InvalidInput)
        }
    }

    private fun RepositoryResult<String>.successValue(): String = when (this) {
        is RepositoryResult.Success -> value
        is RepositoryResult.Failure -> throw AssertionError("Expected success")
    }
}
