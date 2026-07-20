package com.vaultnote.feature.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedAutosaverTest {
    @Test
    fun `save occurs only after four hundred milliseconds without edits`() = runTest {
        val saved = mutableListOf<String>()
        val autosaver = DebouncedAutosaver<String>(
            scope = this,
            save = { value -> saved += value; true },
        )

        autosaver.submit("a")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(saved.isEmpty())

        autosaver.submit("ab")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(saved.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("ab"), saved)
    }

    @Test
    fun `flush saves immediately and pending delay cannot duplicate the write`() = runTest {
        val saved = mutableListOf<String>()
        val autosaver = DebouncedAutosaver<String>(
            scope = this,
            save = { value -> saved += value; true },
        )

        autosaver.submit("draft")

        assertTrue(autosaver.flush())
        advanceUntilIdle()

        assertEquals(listOf("draft"), saved)
    }

    @Test
    fun `an edit made during a slow save is persisted afterward`() = runTest {
        val firstSaveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val autosaver = DebouncedAutosaver<String>(
            scope = this,
            save = { value ->
                if (value == "first") {
                    firstSaveStarted.complete(Unit)
                    releaseFirstSave.await()
                }
                saved += value
                true
            },
        )

        autosaver.submit("first")
        advanceTimeBy(400)
        runCurrent()
        firstSaveStarted.await()

        autosaver.submit("second")
        advanceTimeBy(400)
        runCurrent()
        releaseFirstSave.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), saved)
    }

    @Test
    fun `expired timer never saves a newer edit before its own quiet period`() = runTest {
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val autosaver = DebouncedAutosaver<String>(
            scope = this,
            save = { value ->
                if (value == "blocker") {
                    blockerStarted.complete(Unit)
                    releaseBlocker.await()
                }
                saved += value
                true
            },
        )

        autosaver.submit("blocker")
        advanceTimeBy(400)
        runCurrent()
        blockerStarted.await()

        autosaver.submit("stale")
        advanceTimeBy(400)
        runCurrent()
        autosaver.submit("latest")

        releaseBlocker.complete(Unit)
        runCurrent()
        assertEquals(listOf("blocker"), saved)

        advanceTimeBy(399)
        runCurrent()
        assertEquals(listOf("blocker"), saved)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("blocker", "latest"), saved)
    }

    @Test
    fun `failed snapshot stays pending and flush retries it`() = runTest {
        var attempts = 0
        val autosaver = DebouncedAutosaver<String>(
            scope = this,
            save = {
                attempts += 1
                attempts > 1
            },
        )

        autosaver.submit("draft")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, attempts)

        assertTrue(autosaver.flush())
        assertEquals(2, attempts)
    }

    @Test
    fun `flush reports failure without advancing saved generation`() = runTest {
        val autosaver = DebouncedAutosaver<String>(
            scope = this,
            save = { false },
        )

        autosaver.submit("draft")

        assertFalse(autosaver.flush())
    }
}
