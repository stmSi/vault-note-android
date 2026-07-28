package com.vaultnote.core.sync.lan

import com.vaultnote.core.common.model.DatedEntryType
import com.vaultnote.core.common.model.RecurrenceUnit
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.sync.RemoteAttachmentReference
import com.vaultnote.core.sync.RemoteDatedEntry
import com.vaultnote.core.sync.RemoteItemMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayWireCodecTest {
    @Test
    fun `encrypted metadata payload preserves searchable filename and item fields`() {
        val source = RemoteItemMetadata(
            id = "item_1",
            type = VaultItemType.NOTE,
            title = "Travel",
            body = "Bangkok",
            ocrText = "boarding pass",
            color = VaultItemColor.BLUE,
            isPinned = true,
            isFavorite = true,
            isArchived = false,
            sortPosition = 42L,
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 2_000L,
            clientRevision = 7L,
            tags = listOf("trip", "thai"),
            attachments = listOf(
                RemoteAttachmentReference(
                    id = "attachment_1",
                    remotePath = "/v1/attachments/attachment_1",
                    mimeType = "application/pdf",
                    fileSizeBytes = 4_096L,
                    plaintextSha256 =
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    encryptionFormatVersion = 3,
                    originalFilename = "Boarding Pass Bangkok.pdf",
                    pdfPageCount = 2,
                    createdAtEpochMillis = 1_500L,
                ),
            ),
            bodyDocumentJson = """{"version":1}""",
            datedEntries = listOf(
                RemoteDatedEntry(
                    id = "date_1",
                    type = DatedEntryType.REMINDER,
                    label = "Flight",
                    occurrenceAtEpochMillis = 3_000L,
                    isAllDay = false,
                    timeZoneId = "Asia/Bangkok",
                    recurrenceUnit = RecurrenceUnit.DAY,
                    recurrenceInterval = 1,
                    completedAtEpochMillis = null,
                    createdAtEpochMillis = 1_000L,
                    updatedAtEpochMillis = 2_000L,
                    alertLeadTimesMinutes = listOf(10L, 60L),
                ),
            ),
        )

        val restored = RelayWireCodec.decodeMetadata(
            RelayWireCodec.encodeMetadata(source),
            expectedItemId = source.id,
        )

        assertEquals(source, restored)
        assertEquals("Boarding Pass Bangkok.pdf", restored.attachments.single().originalFilename)
    }

    @Test
    fun `metadata payload cannot be replayed under another item id`() {
        val source = RemoteItemMetadata(
            id = "item_1",
            type = VaultItemType.DOCUMENT,
            title = "",
            body = "",
            ocrText = "",
            color = VaultItemColor.DEFAULT,
            isPinned = false,
            isFavorite = false,
            isArchived = false,
            sortPosition = 1L,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            clientRevision = 1L,
            tags = emptyList(),
            attachments = emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            RelayWireCodec.decodeMetadata(
                RelayWireCodec.encodeMetadata(source),
                expectedItemId = "item_2",
            )
        }
    }
}
