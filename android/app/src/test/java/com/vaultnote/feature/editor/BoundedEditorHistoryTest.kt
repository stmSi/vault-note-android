package com.vaultnote.feature.editor

import com.vaultnote.core.common.model.NoteBlock
import com.vaultnote.core.common.model.NoteBlockType
import com.vaultnote.core.common.model.NoteBodyDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedEditorHistoryTest {
    @Test
    fun `undo and redo move between snapshots in edit order`() {
        val history = BoundedEditorHistory()
        history.record(snapshot("one"))
        history.record(snapshot("two"))

        assertEquals(snapshot("two"), history.undo(snapshot("three")))
        assertEquals(snapshot("one"), history.undo(snapshot("two")))
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(snapshot("two"), history.redo(snapshot("one")))
        assertEquals(snapshot("three"), history.redo(snapshot("two")))
    }

    @Test
    fun `recording a new edit clears redo history`() {
        val history = BoundedEditorHistory()
        history.record(snapshot("one"))
        assertEquals(snapshot("one"), history.undo(snapshot("two")))

        history.record(snapshot("different"))

        assertFalse(history.canRedo)
        assertNull(history.redo(snapshot("new")))
    }

    @Test
    fun `old snapshots are discarded when entry bound is reached`() {
        val history = BoundedEditorHistory(maximumEntries = 2, maximumCharacterWeight = 1_000)
        history.record(snapshot("one"))
        history.record(snapshot("two"))
        history.record(snapshot("three"))

        assertEquals(snapshot("three"), history.undo(snapshot("four")))
        assertEquals(snapshot("two"), history.undo(snapshot("three")))
        assertNull(history.undo(snapshot("two")))
    }

    private fun snapshot(text: String): EditorHistorySnapshot = EditorHistorySnapshot(
        title = text,
        bodyDocument = NoteBodyDocument(
            blocks = listOf(NoteBlock("block", NoteBlockType.PARAGRAPH, text)),
        ),
        tagsText = text,
    )
}
