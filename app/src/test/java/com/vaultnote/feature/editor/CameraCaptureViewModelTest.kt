package com.vaultnote.feature.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.vaultnote.feature.importing.ImportSource
import com.vaultnote.feature.importing.ImportSourceKind
import com.vaultnote.feature.importing.PendingCameraCapture
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraCaptureViewModelTest {
    @Test
    fun `saved state contains only the opaque capture id`() {
        val state = SavedStateHandle()
        val viewModel = CameraCaptureViewModel(state)
        val pending = pendingCapture(CAPTURE_ID)

        viewModel.replace(pending)

        assertEquals(setOf(PENDING_CAPTURE_KEY), state.keys())
        assertEquals(CAPTURE_ID, state.get<String>(PENDING_CAPTURE_KEY))
    }

    @Test
    fun `restored id remains until capture cleanup is confirmed`() {
        val state = SavedStateHandle(mapOf(PENDING_CAPTURE_KEY to CAPTURE_ID))
        val viewModel = CameraCaptureViewModel(state)

        val firstRead = viewModel.peek()
        val secondRead = viewModel.peek()

        assertEquals(CAPTURE_ID, firstRead?.captureId)
        assertNull(firstRead?.inMemoryCapture)
        assertEquals(CAPTURE_ID, secondRead?.captureId)
        assertNull(viewModel.clear("00000000-0000-0000-0000-000000000000"))
        assertEquals(CAPTURE_ID, viewModel.peek()?.captureId)
        assertEquals(CAPTURE_ID, viewModel.clear(CAPTURE_ID)?.captureId)
        assertNull(viewModel.peek())
        assertEquals(emptySet<String>(), state.keys())
    }

    private fun pendingCapture(captureId: String): PendingCameraCapture = PendingCameraCapture(
        captureId = captureId,
        source = ImportSource(
            uri = Uri.parse("content://com.vaultnote.files/camera_captures/capture.jpg"),
            kind = ImportSourceKind.CAMERA_CAPTURE,
            temporaryFile = File("/unused/capture.jpg"),
            captureId = captureId,
        ),
    )

    companion object {
        private const val PENDING_CAPTURE_KEY = "pending_camera_capture_id"
        private const val CAPTURE_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
