package com.vaultnote.core.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSearchHighlightTest {
    private lateinit var database: VaultDatabase
    private lateinit var repository: SearchRepository

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
            repository = RoomSearchRepository(database.searchDao(), TestDispatchers)
            database.vaultItemDao().insert(
                VaultItemEntity(
                    id = ITEM_ID,
                    type = VaultItemType.NOTE,
                    title = "Alpha title",
                    body = "Bravo body",
                    ocrText = "Echo OCR",
                    isPinned = false,
                    isFavorite = false,
                    isArchived = false,
                    createdAt = 1L,
                    updatedAt = 1L,
                    localRevision = 1L,
                    remoteRevision = null,
                    lastSyncedRevision = null,
                    serverVersionToken = null,
                    syncStatus = ItemSyncStatus.PENDING,
                    deletedAt = null,
                    conflictOriginId = null,
                ),
            )
            database.searchDao().insertDocument(
                SearchDocumentEntity(
                    itemId = ITEM_ID,
                    title = "Alpha title",
                    body = "Bravo body",
                    tags = "CharlieTag",
                    attachmentFilenames = "Delta_file.pdf",
                    ocrText = "Echo OCR",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `every indexed source displays its typed prefix highlight`() = runBlocking {
        mapOf(
            "al" to "Al",
            "br" to "Br",
            "ch" to "Ch",
            "del" to "Del",
            "ec" to "Ec",
        ).forEach { (queryText, expectedText) ->
            val compiled = SearchQueryCompiler.compile(queryText) as SearchQueryCompilation.Valid
            val result = repository.observe(compiled.query, 10).first().single()
            val combined = result.highlightedTitle + result.highlightedSnippet
            assertTrue(
                combined.contains(
                    RoomSearchRepository.HIGHLIGHT_START + expectedText +
                        RoomSearchRepository.HIGHLIGHT_END,
                ),
            )
        }
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private companion object {
        const val ITEM_ID = "00000000-0000-0000-0000-000000000001"
    }
}
