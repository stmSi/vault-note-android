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
import org.junit.Assert.assertEquals
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

    @Test
    fun `ordered character fallback matches Bangkok in an attachment filename`() = runBlocking {
        insertSearchableItem(
            id = FUZZY_ITEM_ID,
            title = "Travel files",
            attachmentFilenames = "Bangkok_screenshot.png",
            updatedAt = 2L,
        )

        val compiled = SearchQueryCompiler.compile("bkk") as SearchQueryCompilation.Valid
        val result = repository.observe(compiled.query, 10).first().single()

        assertEquals(FUZZY_ITEM_ID, result.itemId)
        assertTrue(
            result.highlightedSnippet.contains(
                "${RoomSearchRepository.HIGHLIGHT_START}B" +
                    "${RoomSearchRepository.HIGHLIGHT_END}ang" +
                    "${RoomSearchRepository.HIGHLIGHT_START}k" +
                    "${RoomSearchRepository.HIGHLIGHT_END}o" +
                    "${RoomSearchRepository.HIGHLIGHT_START}k" +
                    RoomSearchRepository.HIGHLIGHT_END,
            ),
        )
    }

    @Test
    fun `exact prefix results rank before ordered character fallbacks`() = runBlocking {
        insertSearchableItem(EXACT_ITEM_ID, "BKK airport", updatedAt = 2L)
        insertSearchableItem(FUZZY_ITEM_ID, "Bangkok guide", updatedAt = 3L)

        val compiled = SearchQueryCompiler.compile("bkk") as SearchQueryCompilation.Valid
        val results = repository.observe(compiled.query, 10).first()

        assertEquals(listOf(EXACT_ITEM_ID, FUZZY_ITEM_ID), results.map { it.itemId })
    }

    private suspend fun insertSearchableItem(
        id: String,
        title: String,
        attachmentFilenames: String = "",
        updatedAt: Long,
    ) {
        database.vaultItemDao().insert(
            VaultItemEntity(
                id = id,
                type = VaultItemType.NOTE,
                title = title,
                body = "",
                ocrText = "",
                isPinned = false,
                isFavorite = false,
                isArchived = false,
                createdAt = updatedAt,
                updatedAt = updatedAt,
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
                itemId = id,
                title = title,
                body = "",
                tags = "",
                attachmentFilenames = attachmentFilenames,
                ocrText = "",
            ),
        )
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private companion object {
        const val ITEM_ID = "00000000-0000-0000-0000-000000000001"
        const val EXACT_ITEM_ID = "00000000-0000-0000-0000-000000000002"
        const val FUZZY_ITEM_ID = "00000000-0000-0000-0000-000000000003"
    }
}
