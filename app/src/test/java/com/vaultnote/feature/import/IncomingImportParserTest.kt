package com.vaultnote.feature.importing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.files.MAX_ATTACHMENTS_PER_IMPORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingImportParserTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `rejects a non-content stream URI`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/private.pdf"))
        }

        assertSame(IncomingImportParseResult.UnsupportedUri, IncomingImportParser.parse(intent))
    }

    @Test
    fun `rejects clip data over the import limit before collecting it`() {
        val clip = ClipData.newUri(
            context.contentResolver,
            "files",
            Uri.parse("content://provider/0"),
        )
        repeat(MAX_ATTACHMENTS_PER_IMPORT) { index ->
            clip.addItem(ClipData.Item(Uri.parse("content://provider/${index + 1}")))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { clipData = clip }

        assertSame(IncomingImportParseResult.TooManyFiles, IncomingImportParser.parse(intent))
    }

    @Test
    fun `deduplicates a content URI represented in clip data and stream extra`() {
        val uri = Uri.parse("content://provider/document")
        val intent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newUri(context.contentResolver, "file", uri)
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val parsed = IncomingImportParser.parse(intent) as IncomingImportParseResult.Accepted

        assertEquals(listOf(uri), parsed.incomingImport.sources.map(ImportSource::uri))
    }

    @Test
    fun `accepts shared text without a stream`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Offline note")

        val parsed = IncomingImportParser.parse(intent) as IncomingImportParseResult.Accepted

        assertEquals("Offline note", parsed.incomingImport.sharedText)
        assertTrue(parsed.incomingImport.sources.isEmpty())
    }

    @Test
    fun `coordinator consumes sensitive payload only once`() {
        val coordinator = IncomingImportCoordinator()
        val payload = IncomingImport(sharedText = "private", sources = emptyList())

        val token = coordinator.offer(payload)

        assertEquals(payload, coordinator.take(token))
        assertNull(coordinator.take(token))
    }
}
