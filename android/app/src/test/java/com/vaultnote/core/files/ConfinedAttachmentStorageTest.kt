package com.vaultnote.core.files

import com.vaultnote.core.common.RepositoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedAttachmentStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `resolves generated paths only inside their dedicated roots`() {
        val root = temporaryFolder.newFolder("vault")
        val storage = ConfinedAttachmentStorage(root)
        assertTrue(storage.ensureDirectories() is RepositoryResult.Success)

        val attachment = storage.resolveAttachment("attachments/id_123.bin")
        val thumbnail = storage.resolveThumbnail("thumbnails/id_123.webp")

        assertEquals(root.resolve("attachments/id_123.bin").canonicalFile, attachment.successValue())
        assertEquals(root.resolve("thumbnails/id_123.webp").canonicalFile, thumbnail.successValue())
    }

    @Test
    fun `rejects traversal absolute cross-root and backslash paths`() {
        val storage = ConfinedAttachmentStorage(temporaryFolder.newFolder("vault-unsafe"))
        assertTrue(storage.ensureDirectories() is RepositoryResult.Success)

        listOf(
            "attachments/../outside.bin",
            "/attachments/id.bin",
            "thumbnails/id.webp",
            "attachments\\id.bin",
            "attachments/nested/id.bin",
        ).forEach { path ->
            assertTrue(storage.resolveAttachment(path) is RepositoryResult.Failure)
        }
    }

    @Test
    fun `rejects unsafe attachment identifiers before path construction`() {
        val storage = ConfinedAttachmentStorage(temporaryFolder.newFolder("vault-id"))

        listOf("", "../id", "id/name", "id.name").forEach { id ->
            assertTrue(storage.attachmentRelativePath(id) is RepositoryResult.Failure)
        }
        assertEquals(
            "attachments/id-123_A.bin",
            storage.attachmentRelativePath("id-123_A").successValue(),
        )
    }

    private fun <T> RepositoryResult<T>.successValue(): T = when (this) {
        is RepositoryResult.Success -> value
        is RepositoryResult.Failure -> throw AssertionError("Expected success")
    }
}
