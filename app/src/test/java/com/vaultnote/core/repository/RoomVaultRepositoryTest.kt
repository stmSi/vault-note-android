package com.vaultnote.core.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.sync.CoalescingFakeSyncScheduler
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomVaultRepositoryTest {
    private lateinit var database: VaultDatabase
    private lateinit var scheduler: CoalescingFakeSyncScheduler
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        scheduler = CoalescingFakeSyncScheduler()
        repository = RoomVaultRepository(
            database = database,
            syncScheduler = scheduler,
            dispatchers = TestDispatchers,
            clock = IncrementingClock(),
            idGenerator = SequenceIdGenerator(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create and save update Room search tags and one coalesced sync slot`() = runBlocking {
        val itemId = repository.createNote().successValue()
        val initialNote = requireNotNull(repository.observeNote(itemId).first())
        val initialOperation = requireNotNull(
            database.syncOperationDao().getByDedupeKey("item:$itemId"),
        )

        assertEquals(1L, initialNote.localRevision)
        assertEquals(SyncOperationType.UPSERT_ITEM, initialOperation.operationType)
        assertEquals(1L, initialOperation.targetRevision)
        assertEquals(
            1,
            database.syncOperationDao().updateAttemptState(
                operationId = initialOperation.operationId,
                state = SyncOperationState.RUNNING,
                attemptCount = 1,
                nextAttemptAt = 2_000L,
                leaseToken = "worker-lease",
                leaseExpiresAt = 3_000L,
                updatedAt = 2_000L,
                lastErrorCode = null,
            ),
        )

        repository.saveNote(
            id = itemId,
            title = "Offline plan",
            body = "Keep this available",
            tagNames = listOf(" Work ", "work", "Personal"),
        ).requireSuccess()

        val savedNote = requireNotNull(repository.observeNote(itemId).first())
        val savedOperation = requireNotNull(
            database.syncOperationDao().getByDedupeKey("item:$itemId"),
        )
        val searchDocument = requireNotNull(database.searchDao().getDocumentForItem(itemId))

        assertEquals("Offline plan", savedNote.title)
        assertEquals("Keep this available", savedNote.body)
        assertEquals(listOf("Personal", "Work"), savedNote.tags.map { it.name })
        assertEquals(2L, savedNote.localRevision)
        assertEquals(2L, savedOperation.targetRevision)
        assertNotEquals(initialOperation.operationId, savedOperation.operationId)
        assertNull(savedOperation.leaseToken)
        assertNull(savedOperation.leaseExpiresAt)
        assertEquals("Offline plan", searchDocument.title)
        assertEquals("Personal\nWork", searchDocument.tags)
        assertEquals(1L, scheduler.acceptedRequestCount)

        repository.saveNote(
            id = itemId,
            title = savedNote.title,
            body = savedNote.body,
            tagNames = savedNote.tags.map { it.name },
        ).requireSuccess()

        val unchanged = requireNotNull(repository.observeNote(itemId).first())
        val unchangedOperation = requireNotNull(
            database.syncOperationDao().getByDedupeKey("item:$itemId"),
        )
        assertEquals(2L, unchanged.localRevision)
        assertEquals(savedOperation.operationId, unchangedOperation.operationId)
    }

    @Test
    fun `pin favorite and archive commands change only intended metadata`() = runBlocking {
        val itemId = repository.createNote("Title", "Body").successValue()

        repository.setPinned(itemId, true).requireSuccess()
        repository.setFavorite(itemId, true).requireSuccess()
        repository.setArchived(itemId, true).requireSuccess()

        val archived = requireNotNull(repository.observeNote(itemId).first())
        assertTrue(archived.isPinned)
        assertTrue(archived.isFavorite)
        assertTrue(archived.isArchived)
        assertEquals("Title", archived.title)
        assertEquals("Body", archived.body)
        assertEquals(4L, archived.localRevision)
        assertTrue(repository.observeActiveItems().first().isEmpty())
        assertEquals(listOf(itemId), repository.observeArchivedItems().first().map { it.id })

        repository.setArchived(itemId, false).requireSuccess()
        assertEquals(listOf(itemId), repository.observeActiveItems().first().map { it.id })
        assertTrue(repository.observeArchivedItems().first().isEmpty())
    }

    @Test
    fun `soft deletion hides content and restore preserves it`() = runBlocking {
        val itemId = repository.createNote("Recoverable", "Body").successValue()

        repository.moveToTrash(itemId).requireSuccess()

        val deleted = requireNotNull(repository.observeNote(itemId).first())
        val deleteOperation = requireNotNull(
            database.syncOperationDao().getByDedupeKey("item:$itemId"),
        )
        assertNotNull(deleted.deletedAtEpochMillis)
        assertEquals("Recoverable", deleted.title)
        assertTrue(repository.observeActiveItems().first().isEmpty())
        assertEquals(listOf(itemId), repository.observeTrashItems().first().map { it.id })
        assertEquals(SyncOperationType.DELETE_ITEM, deleteOperation.operationType)

        repository.restore(itemId).requireSuccess()

        val restored = requireNotNull(repository.observeNote(itemId).first())
        val restoreOperation = requireNotNull(
            database.syncOperationDao().getByDedupeKey("item:$itemId"),
        )
        assertNull(restored.deletedAtEpochMillis)
        assertEquals("Recoverable", restored.title)
        assertEquals(SyncOperationType.UPSERT_ITEM, restoreOperation.operationType)
        assertEquals(listOf(itemId), repository.observeActiveItems().first().map { it.id })
        assertTrue(repository.observeTrashItems().first().isEmpty())
    }

    @Test
    fun `invalid tag fails without mutating the note`() = runBlocking {
        val itemId = repository.createNote("Title", "Body").successValue()
        val before = requireNotNull(repository.observeNote(itemId).first())

        val result = repository.saveNote(
            id = itemId,
            title = "Changed",
            body = "Changed",
            tagNames = listOf("bad\u0000tag"),
        )

        assertTrue(result is RepositoryResult.Failure)
        val after = requireNotNull(repository.observeNote(itemId).first())
        assertEquals(before, after)
    }

    @Test
    fun `note limits count Unicode code points and reject malformed text`() = runBlocking {
        val maximumEmojiTitle = "😀".repeat(500)
        val validResult = repository.createNote(maximumEmojiTitle, "Body")
        assertTrue(validResult is RepositoryResult.Success)

        val oversized = repository.createNote("😀".repeat(501), "Body")
        val malformed = repository.createNote("\uD83D", "Body")

        assertTrue(oversized is RepositoryResult.Failure)
        assertTrue(malformed is RepositoryResult.Failure)
        assertEquals(1, repository.observeActiveItems().first().size)
    }

    @Test
    fun `offset windows keep every note reachable without loading the full vault`() = runBlocking {
        val ids = (1..5).map { index ->
            repository.createNote("Note $index", "").successValue()
        }

        val firstWindow = repository.observeActiveItems(limit = 2, offset = 0).first()
        val secondWindow = repository.observeActiveItems(limit = 2, offset = 2).first()
        val lastWindow = repository.observeActiveItems(limit = 2, offset = 4).first()

        assertEquals(ids.asReversed().take(2), firstWindow.map { it.id })
        assertEquals(ids.asReversed().drop(2).take(2), secondWindow.map { it.id })
        assertEquals(ids.asReversed().drop(4), lastWindow.map { it.id })
    }

    private fun <T> RepositoryResult<T>.successValue(): T = when (this) {
        is RepositoryResult.Success -> value
        is RepositoryResult.Failure -> throw AssertionError("Expected success, got ${error::class.simpleName}")
    }

    private fun RepositoryResult<Unit>.requireSuccess() {
        assertFalse(this is RepositoryResult.Failure)
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private class IncrementingClock : Clock {
        private var now = 1_000L

        override fun nowEpochMillis(): Long = now++
    }

    private class SequenceIdGenerator : IdGenerator {
        private val next = AtomicInteger(1)

        override fun newId(): String = "00000000-0000-0000-0000-${next.getAndIncrement().toString().padStart(12, '0')}"
    }
}
