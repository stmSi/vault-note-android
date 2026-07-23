package com.vaultnote.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.files.AndroidAttachmentFileManager
import com.vaultnote.core.repository.RoomVaultRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSyncRepositoryTest {
    private lateinit var database: VaultDatabase
    private lateinit var backend: InMemoryFakeSyncBackend
    private lateinit var syncRepository: RoomSyncRepository
    private lateinit var vaultRepository: RoomVaultRepository
    private lateinit var ids: SequenceIdGenerator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        backend = InMemoryFakeSyncBackend(TestDispatchers)
        ids = SequenceIdGenerator()
        val scheduler = CoalescingFakeSyncScheduler()
        val clock = IncrementingClock()
        vaultRepository = RoomVaultRepository(
            database,
            scheduler,
            TestDispatchers,
            clock,
            ids,
        )
        syncRepository = RoomSyncRepository(
            database = database,
            syncApi = backend,
            authProvider = backend,
            remoteFileStore = backend,
            fileManager = AndroidAttachmentFileManager(context, TestDispatchers),
            syncScheduler = scheduler,
            dispatchers = TestDispatchers,
            clock = clock,
            idGenerator = ids,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `queued note synchronizes idempotently and records revisions`() = runBlocking {
        val itemId = vaultRepository.createNote("Offline", "First").successValue()

        val first = syncRepository.synchronize()
        val second = syncRepository.synchronize()
        val note = requireNotNull(vaultRepository.observeNote(itemId).first())

        assertTrue(first is SyncRunResult.Completed)
        assertEquals(1, (first as SyncRunResult.Completed).processedOperations)
        assertEquals(0, (second as SyncRunResult.Completed).processedOperations)
        assertEquals(ItemSyncStatus.SYNCED, note.syncStatus)
        assertNotNull(note.remoteRevision)
        assertNotNull(note.serverVersionToken)
        assertEquals(note.localRevision, note.lastSyncedRevision)
        assertEquals(0L, database.syncOperationDao().observeOutstandingCount(
            com.vaultnote.core.common.model.SyncOperationState.COMPLETED,
        ).first())
    }

    @Test
    fun `concurrent content changes preserve both versions and selected version resolves`() = runBlocking {
        val itemId = vaultRepository.createNote("Plan", "Original").successValue()
        syncRepository.synchronize()
        val synced = requireNotNull(vaultRepository.observeNote(itemId).first())

        val remoteChange = backend.upsertItem(
            operationId = "remote-change",
            item = synced.toRemoteMetadata(body = "Remote edit"),
            expectedVersionToken = synced.serverVersionToken,
        )
        assertTrue(remoteChange is RemoteMutationResult.Applied)
        vaultRepository.saveNote(itemId, "Plan", "Local edit").requireSuccess()

        syncRepository.synchronize()
        val conflicts = syncRepository.observeConflicts().first()

        assertEquals(2, conflicts.size)
        assertTrue(conflicts.any { it.bodyPreview == "Local edit" && it.conflictOriginId == null })
        val remoteCopy = conflicts.first { it.bodyPreview == "Remote edit" }
        assertNotNull(remoteCopy.conflictOriginId)

        syncRepository.resolveConflict(remoteCopy.id).requireSuccess()
        val resolved = requireNotNull(vaultRepository.observeNote(itemId).first())
        assertEquals("Remote edit", resolved.body)
        assertEquals(ItemSyncStatus.PENDING, resolved.syncStatus)
        assertTrue(syncRepository.observeConflicts().first().isEmpty())

        syncRepository.synchronize()
        assertEquals(
            ItemSyncStatus.SYNCED,
            requireNotNull(vaultRepository.observeNote(itemId).first()).syncStatus,
        )
    }

    @Test
    fun `expired authentication stops without indefinite retry`() = runBlocking {
        val itemId = vaultRepository.createNote("Private", "Body").successValue()
        backend.setAuthenticationState(AuthenticationState.EXPIRED)

        assertEquals(SyncRunResult.AuthenticationRequired, syncRepository.synchronize())
        val operation = requireNotNull(database.syncOperationDao().getByDedupeKey("item:$itemId"))
        assertEquals(com.vaultnote.core.common.model.SyncOperationState.PENDING, operation.state)
    }

    private fun com.vaultnote.core.common.model.VaultNote.toRemoteMetadata(
        body: String,
    ): RemoteItemMetadata = RemoteItemMetadata(
        id = id,
        type = com.vaultnote.core.common.model.VaultItemType.NOTE,
        title = title,
        body = body,
        ocrText = ocrText,
        color = color,
        isPinned = isPinned,
        isFavorite = isFavorite,
        isArchived = isArchived,
        sortPosition = 0L,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis + 1L,
        clientRevision = localRevision + 1L,
        tags = tags.map { it.name },
        attachments = emptyList(),
    )

    private fun <T> RepositoryResult<T>.successValue(): T = when (this) {
        is RepositoryResult.Success -> value
        is RepositoryResult.Failure -> error("Expected success: ${error::class.simpleName}")
    }

    private fun RepositoryResult<Unit>.requireSuccess() {
        if (this is RepositoryResult.Failure) error("Expected success: ${error::class.simpleName}")
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
        override fun newId(): String =
            "00000000-0000-0000-0000-${next.getAndIncrement().toString().padStart(12, '0')}"
    }
}
