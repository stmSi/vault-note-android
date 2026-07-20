package com.vaultnote.feature.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraCaptureManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `restores a capture from only its validated id and deletes it safely`() = runTest {
        val manager = CameraCaptureManager(
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val created = manager.createCapture().getOrThrow()
        val originalFile = requireNotNull(created.source.temporaryFile)
        try {
            val restored = manager.restoreCapture(created.captureId)

            assertEquals(created.captureId, restored?.captureId)
            assertEquals(originalFile.canonicalFile, restored?.source?.temporaryFile?.canonicalFile)
            assertEquals("content", restored?.source?.uri?.scheme)
            manager.deleteCapture(restored)
            assertFalse(originalFile.exists())
        } finally {
            manager.deleteCapture(created)
        }
    }

    @Test
    fun `rejects a path-like capture id without touching cache files`() = runTest {
        val manager = CameraCaptureManager(
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val cacheDirectory = File(context.cacheDir, CameraCaptureManager.CAPTURE_DIRECTORY)
        val filesBefore = cacheDirectory.listFiles()?.map { it.name }?.toSet().orEmpty()

        val restored = manager.restoreCapture("../outside")

        assertNull(restored)
        assertEquals(filesBefore, cacheDirectory.listFiles()?.map { it.name }?.toSet().orEmpty())
    }
}
