package com.vaultnote.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.vaultnote.feature.importing.PendingCameraCapture

/** Saves only an opaque capture UUID; file paths, content URIs, and captured content stay in memory. */
internal data class PendingCameraReference(
    val captureId: String,
    val inMemoryCapture: PendingCameraCapture?,
)

internal class CameraCaptureViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var inMemoryCapture: PendingCameraCapture? = null

    fun replace(capture: PendingCameraCapture): PendingCameraReference? {
        val previous = currentReference()
        inMemoryCapture = capture
        savedStateHandle[PENDING_CAPTURE_ID] = capture.captureId
        return previous
    }

    fun peek(): PendingCameraReference? = currentReference()

    fun clear(captureId: String): PendingCameraReference? {
        val reference = currentReference()?.takeIf { it.captureId == captureId } ?: return null
        inMemoryCapture = null
        savedStateHandle.remove<String>(PENDING_CAPTURE_ID)
        return reference
    }

    private fun currentReference(): PendingCameraReference? {
        val captureId = savedStateHandle.get<String>(PENDING_CAPTURE_ID) ?: return null
        return PendingCameraReference(
            captureId = captureId,
            inMemoryCapture = inMemoryCapture?.takeIf { it.captureId == captureId },
        )
    }

    private companion object {
        const val PENDING_CAPTURE_ID = "pending_camera_capture_id"
    }
}
