package com.vaultnote.feature.editor

import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.vaultnote.R
import com.vaultnote.core.common.model.NoteBlock
import com.vaultnote.core.common.model.NoteBlockType
import com.vaultnote.core.common.model.NoteBodyDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteBlockAdapterTest {
    @Test
    fun `enter advances focus without repeating the body hint`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_VaultNote)
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
        }
        var requestedPosition = -1
        val adapter = NoteBlockAdapter(
            onDocumentChanged = {},
            onBodyFocusChanged = {},
            onBlockFocusRequested = { position ->
                requestedPosition = position
                recyclerView.scrollToPosition(position)
            },
        )
        recyclerView.adapter = adapter
        activity.setContentView(recyclerView)
        adapter.submitDocument(
            NoteBodyDocument(
                blocks = listOf(
                    NoteBlock(
                        id = "first",
                        type = NoteBlockType.PARAGRAPH,
                        text = "First",
                    ),
                ),
            ),
        )
        layout(recyclerView)

        val first = editorAt(recyclerView, 0)
        assertEquals("Note", first.hint)
        first.requestFocus()
        first.text?.append('\n')
        layout(recyclerView)

        assertEquals(2, adapter.itemCount)
        assertEquals(1, requestedPosition)
        assertNull(editorAt(recyclerView, 0).hint)
        val second = editorAt(recyclerView, 1)
        assertNull(second.hint)
        assertTrue(second.isFocused)

        second.text?.append('\n')
        layout(recyclerView)

        assertEquals(3, adapter.itemCount)
        assertEquals(2, requestedPosition)
        assertTrue(editorAt(recyclerView, 2).isFocused)
    }

    private fun editorAt(recyclerView: RecyclerView, position: Int): TextInputEditText {
        val holder = requireNotNull(recyclerView.findViewHolderForAdapterPosition(position))
        return holder.itemView.findViewById(R.id.text)
    }

    private fun layout(recyclerView: RecyclerView) {
        val width = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        shadowOf(Looper.getMainLooper()).idle()
        recyclerView.measure(width, height)
        recyclerView.layout(0, 0, 480, 800)
        shadowOf(Looper.getMainLooper()).idle()
        recyclerView.measure(width, height)
        recyclerView.layout(0, 0, 480, 800)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
