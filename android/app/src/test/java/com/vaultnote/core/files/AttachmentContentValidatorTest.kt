package com.vaultnote.core.files

import android.graphics.Bitmap
import android.graphics.Color
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentContentValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = AttachmentContentValidator()

    @Test
    fun `accepts PDF preview with spaces and a generic provider MIME`() {
        val sample = ContentSample(
            "%PDF-1.7\npreview body".toByteArray(),
            isTruncated = true,
        )

        val result = validator.validatePreview(
            "VaultNote Development Plan.pdf",
            "application/octet-stream",
            sample,
        )

        assertTrue(result is RepositoryResult.Success)
        assertEquals(
            AttachmentFormat.PDF,
            (result as RepositoryResult.Success).value.format,
        )
    }

    @Test
    fun `rejects a PDF renamed and claimed as PNG`() {
        val sample = ContentSample("%PDF-1.7\n%%EOF".toByteArray(), isTruncated = false)

        val result = validator.validatePreview("scan.png", "image/png", sample)

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.UnsupportedFile, (result as RepositoryResult.Failure).error)
    }

    @Test
    fun `rejects unknown extensions even when content has a supported signature`() {
        val sample = ContentSample("%PDF-1.7\n%%EOF".toByteArray(), isTruncated = false)

        val result = validator.validatePreview("scan.exe", "application/octet-stream", sample)

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.UnsupportedFile, (result as RepositoryResult.Failure).error)
    }

    @Test
    fun `accepts a JPEG with Samsung-style motion photo data`() = runTest {
        val file = temporaryFolder.newFile("motion-photo.jpg").apply {
            writeBytes(
                androidJpeg() +
                    byteArrayOf(0x00, 0x00, 0x30, 0x0A, 0x10, 0x00, 0x00, 0x00) +
                    "MotionPhoto_Data".toByteArray(StandardCharsets.US_ASCII) +
                    minimalMp4() +
                    "SEFT".toByteArray(StandardCharsets.US_ASCII),
            )
        }

        val result = validator.validateStored(file, file.name, "image/jpeg")

        assertTrue(result is RepositoryResult.Success)
        assertEquals(AttachmentFormat.JPEG, (result as RepositoryResult.Success).value.format)
    }

    @Test
    fun `accepts an ordinary JPEG produced by Android`() = runTest {
        val file = temporaryFolder.newFile("camera.jpg").apply { writeBytes(androidJpeg()) }

        val result = validator.validateStored(file, file.name, "image/jpeg")

        assertTrue(result is RepositoryResult.Success)
    }

    @Test
    fun `accepts a JPEG with a standard appended motion photo video`() = runTest {
        val file = temporaryFolder.newFile("standard-motion.jpg").apply {
            writeBytes(androidJpeg() + minimalMp4())
        }

        val result = validator.validateStored(file, file.name, "image/jpeg")

        assertTrue(result is RepositoryResult.Success)
    }

    @Test
    fun `accepts a decodable JPEG with trailing vendor metadata`() = runTest {
        val file = temporaryFolder.newFile("vendor.jpg").apply {
            writeBytes(androidJpeg() + "vendor metadata".toByteArray(StandardCharsets.US_ASCII))
        }

        val result = validator.validateStored(file, file.name, "image/jpeg")

        assertTrue(result is RepositoryResult.Success)
    }

    @Test
    fun `accepts a decodable screenshot PNG with trailing metadata`() = runTest {
        val file = temporaryFolder.newFile("Screenshot.png").apply {
            writeBytes(androidImage(Bitmap.CompressFormat.PNG) + byteArrayOf(0x00, 0x01, 0x02))
        }

        val result = validator.validateStored(file, file.name, "image/png")

        assertTrue(result is RepositoryResult.Success)
    }

    @Test
    fun `rejects a truncated JPEG scan`() = runTest {
        val file = temporaryFolder.newFile("truncated.jpg").apply {
            writeBytes(androidJpeg().copyOf(64))
        }

        val result = validator.validateStored(file, file.name, "image/jpeg")

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.CorruptedFile, (result as RepositoryResult.Failure).error)
    }

    @Test
    fun `rejects malformed JSON during authoritative validation`() = runTest {
        val file = temporaryFolder.newFile("invalid.json").apply {
            writeText("{\"valid\": true} trailing", StandardCharsets.UTF_8)
        }

        val result = validator.validateStored(file, file.name, "application/json")

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.CorruptedFile, (result as RepositoryResult.Failure).error)
    }

    @Test
    fun `rejects office containers containing traversal entries`() = runTest {
        val file = temporaryFolder.newFile("unsafe.docx")
        writeDocx(file, includeTraversal = true)

        val result = validator.validateStored(file, file.name, AttachmentFormat.DOCX.canonicalMimeType)

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.CorruptedFile, (result as RepositoryResult.Failure).error)
    }

    @Test
    fun `accepts structurally valid macro-free docx`() = runTest {
        val file = temporaryFolder.newFile("document.docx")
        writeDocx(file, includeTraversal = false)

        val result = validator.validateStored(file, file.name, AttachmentFormat.DOCX.canonicalMimeType)

        assertTrue(result is RepositoryResult.Success)
        assertEquals(
            AttachmentFormat.DOCX,
            (result as RepositoryResult.Success).value.format,
        )
    }

    private fun writeDocx(file: File, includeTraversal: Boolean) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.addEntry(
                "[Content_Types].xml",
                """
                    <?xml version="1.0"?>
                    <Types><Override ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>
                """.trimIndent(),
            )
            zip.addEntry("_rels/.rels", "<?xml version=\"1.0\"?><Relationships/>")
            zip.addEntry("word/document.xml", "<?xml version=\"1.0\"?><w:document/>")
            if (includeTraversal) zip.addEntry("../outside", "blocked")
        }
    }

    private fun ZipOutputStream.addEntry(name: String, contents: String) {
        putNextEntry(ZipEntry(name))
        write(contents.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun androidJpeg(): ByteArray = androidImage(Bitmap.CompressFormat.JPEG)

    private fun androidImage(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun minimalMp4(): ByteArray = byteArrayOf(
        0x00,
        0x00,
        0x00,
        0x10,
        0x66,
        0x74,
        0x79,
        0x70,
        0x69,
        0x73,
        0x6F,
        0x6D,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x0C,
        0x6D,
        0x64,
        0x61,
        0x74,
        0x01,
        0x02,
        0x03,
        0x04,
    )
}
