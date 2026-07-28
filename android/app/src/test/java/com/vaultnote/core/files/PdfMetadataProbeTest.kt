package com.vaultnote.core.files

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfMetadataProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `security failure identifies a password-protected PDF`() {
        val result = classifyPdfMetadata { throw SecurityException("password required") }

        assertEquals(PdfMetadataProbe.PasswordProtected, result)
    }

    @Test
    fun `generic IO failure remains unreadable`() {
        val result = classifyPdfMetadata { throw IOException("invalid xref") }

        assertEquals(PdfMetadataProbe.Unreadable, result)
    }

    @Test
    fun `positive renderer page count is retained`() {
        val result = classifyPdfMetadata { 12 }

        assertEquals(PdfMetadataProbe.Readable(12), result)
    }

    @Test
    fun `encryption dictionary marker is found across scan buffers`() = runTest {
        val prefix = ByteArray((64 * 1024) - 4) { 'x'.code.toByte() }
        val file = temporaryFolder.newFile("protected.pdf").apply {
            writeBytes(
                prefix +
                    "/Encrypt 8 0 R\n%%EOF".encodeToByteArray(),
            )
        }

        assertTrue(containsPdfEncryptionDictionary(file))
    }

    @Test
    fun `longer PDF name is not mistaken for encryption dictionary`() = runTest {
        val file = temporaryFolder.newFile("ordinary.pdf").apply {
            writeText("%PDF-1.7\n/EncryptMetadata false\n%%EOF")
        }

        assertFalse(containsPdfEncryptionDictionary(file))
    }
}
