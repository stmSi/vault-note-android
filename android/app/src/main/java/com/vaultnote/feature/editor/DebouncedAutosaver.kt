package com.vaultnote.feature.editor

import androidx.annotation.MainThread
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists immutable editor snapshots after a quiet period while keeping actual writes serialized.
 * A new edit cancels only the delay; it never cancels a write that has already started.
 */
internal class DebouncedAutosaver<T : Any>(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val save: suspend (T) -> Boolean,
    private val onSaveStarted: (version: Long) -> Unit = {},
    private val onSaveFinished: (version: Long, succeeded: Boolean) -> Unit = { _, _ -> },
) {
    private data class VersionedValue<T>(val version: Long, val value: T)

    private val nextVersion = AtomicLong(0L)
    private val savedVersion = AtomicLong(0L)
    private val latest = AtomicReference<VersionedValue<T>?>(null)
    private val saveMutex = Mutex()
    private val delayJob = AtomicReference<Job?>(null)

    @MainThread
    fun submit(value: T): Long {
        val version = nextVersion.incrementAndGet()
        latest.set(VersionedValue(version, value))
        lateinit var scheduledJob: Job
        scheduledJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(debounceMillis)
            delayJob.compareAndSet(scheduledJob, null)
            persistScheduledVersion(version)
        }
        delayJob.getAndSet(scheduledJob)?.cancel()
        scheduledJob.start()
        return version
    }

    suspend fun flush(): Boolean {
        delayJob.getAndSet(null)?.cancelAndJoin()
        return persistLatest(drainNewerEdits = true)
    }

    @MainThread
    fun cancelPendingDelay() {
        delayJob.getAndSet(null)?.cancel()
    }

    private suspend fun persistScheduledVersion(version: Long): Boolean = saveMutex.withLock {
        val snapshot = latest.get() ?: return@withLock true
        if (snapshot.version != version || snapshot.version <= savedVersion.get()) {
            return@withLock true
        }
        persistSnapshot(snapshot)
    }

    private suspend fun persistLatest(drainNewerEdits: Boolean): Boolean = saveMutex.withLock {
        var succeeded = true
        var shouldContinue = true
        while (shouldContinue) {
            val snapshot = latest.get() ?: break
            if (snapshot.version <= savedVersion.get()) break

            succeeded = persistSnapshot(snapshot)

            if (!succeeded) break
            val newestVersion = latest.get()?.version ?: snapshot.version
            shouldContinue = drainNewerEdits && newestVersion > savedVersion.get()
        }
        succeeded
    }

    private suspend fun persistSnapshot(snapshot: VersionedValue<T>): Boolean {
        onSaveStarted(snapshot.version)
        val succeeded = save(snapshot.value)
        if (succeeded) {
            savedVersion.accumulateAndGet(snapshot.version, ::maxOf)
        }
        onSaveFinished(snapshot.version, succeeded)
        return succeeded
    }

    internal companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 400L
    }
}
