package com.vaultnote.core.common

import com.vaultnote.core.common.model.NoteBlock
import com.vaultnote.core.common.model.NoteBlockType
import com.vaultnote.core.common.model.NoteBodyDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteBodyCodecTest {
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
        assertNull(NoteBodyCodec.decodeOrNull("""{"version":2,"blocks":[]}"""))
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
