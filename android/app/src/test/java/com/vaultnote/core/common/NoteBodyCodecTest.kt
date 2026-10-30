package com.vaultnote.core.common

import com.vaultnote.core.common.model.NoteBlock
import com.vaultnote.core.common.model.NoteBlockType
import com.vaultnote.core.common.model.NoteBodyDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteBodyCodecTest {
    @Test
    fun `read only settings survive encoding without changing searchable text`() {
        val document = NoteBodyDocument(
            blocks = listOf(NoteBlock("personal", NoteBlockType.PARAGRAPH, "Passport details", isReadOnly = true)),
            isReadOnly = true,
        )
        assertEquals(document, NoteBodyCodec.decodeOrNull(NoteBodyCodec.encode(document)))
        assertEquals("Passport details", NoteBodyCodec.derivePlainText(document))
    }

    @Test
    fun `legacy documents remain editable`() {
        val document = requireNotNull(NoteBodyCodec.decodeOrNull(
            """{"version":1,"blocks":[{"id":"one","type":"PARAGRAPH","text":"Existing note","checked":false}]}""",
        ))
        assertFalse(document.isReadOnly)
        assertFalse(document.blocks.single().isReadOnly)
        assertEquals("Existing note", NoteBodyCodec.derivePlainText(document))
    }

    @Test
    fun `locked lines cannot be changed removed unchecked or unlocked by content saves`() {
        val locked = NoteBlock("locked", NoteBlockType.CHECKLIST_ITEM, "Personal info", isChecked = true, isReadOnly = true)
        val current = NoteBodyDocument(blocks = listOf(locked))
        listOf(
            emptyList(),
            listOf(locked.copy(text = "Changed")),
            listOf(locked.copy(isChecked = false)),
            listOf(locked.copy(isReadOnly = false)),
            listOf(locked.copy(id = "replacement")),
        ).forEach { blocks -> assertFalse(NoteEditPolicy.allowsBodyChange(current, current.copy(blocks = blocks))) }
        assertTrue(NoteEditPolicy.allowsBodyChange(current, current.copy(
            blocks = listOf(locked, NoteBlock("new", NoteBlockType.PARAGRAPH, "More details")),
        )))
        assertFalse(NoteEditPolicy.allowsBodyChange(current, current.copy(isReadOnly = true)))
    }
    @Test
    fun `mixed blocks round trip and derive searchable text`() {
        val document = NoteBodyDocument(
            blocks = listOf(
                NoteBlock("paragraph", NoteBlockType.PARAGRAPH, "Renew passport"),
                NoteBlock("unchecked", NoteBlockType.CHECKLIST_ITEM, "Take photo"),
                NoteBlock(
                    "checked",
                    NoteBlockType.CHECKLIST_ITEM,
                    "Find old passport",
                    isChecked = true,
                ),
            ),
        )

        val encoded = NoteBodyCodec.encode(document)

        assertEquals(document, NoteBodyCodec.decodeOrNull(encoded))
        assertEquals(
            "Renew passport\n[ ] Take photo\n[x] Find old passport",
            NoteBodyCodec.derivePlainText(document),
        )
    }

    @Test
    fun `plain text becomes stable paragraph blocks`() {
        var nextId = 0

        val document = NoteBodyCodec.fromPlainText("First\nSecond") {
            "block-${++nextId}"
        }

        assertEquals(
            listOf(
                NoteBlock("block-1", NoteBlockType.PARAGRAPH, "First"),
                NoteBlock("block-2", NoteBlockType.PARAGRAPH, "Second"),
            ),
            document.blocks,
        )
    }

    @Test
    fun `unknown versions duplicate IDs and paragraph checks are rejected`() {
        assertNull(NoteBodyCodec.decodeOrNull("""{"version":3,"blocks":[]}"""))
        assertNull(
            NoteBodyCodec.decodeOrNull(
                """
                {
                  "version": 1,
                  "blocks": [
                    {"id":"same","type":"PARAGRAPH","text":"One","checked":false},
                    {"id":"same","type":"PARAGRAPH","text":"Two","checked":false}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertNull(
            NoteBodyCodec.decodeOrNull(
                """
                {
                  "version": 1,
                  "blocks": [
                    {"id":"paragraph","type":"PARAGRAPH","text":"No","checked":true}
                  ]
                }
                """.trimIndent(),
            ),
        )
    }
}
