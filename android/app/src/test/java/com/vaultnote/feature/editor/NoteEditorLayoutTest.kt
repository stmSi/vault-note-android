package com.vaultnote.feature.editor

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.R
import com.vaultnote.databinding.FragmentNoteEditorBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteEditorLayoutTest {
    @Test
    fun `system inset controls grow instead of clipping their content`() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_VaultNote,
        )
        val binding = FragmentNoteEditorBinding.inflate(LayoutInflater.from(context))
        val touchTarget = context.resources.getDimensionPixelSize(R.dimen.touch_target)

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, binding.toolbar.layoutParams.height)
        assertTrue(binding.toolbar.minimumHeight >= touchTarget)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, binding.editorActionBar.layoutParams.height)
        assertEquals(touchTarget, binding.editorActionBar.minimumHeight)
    }
}
