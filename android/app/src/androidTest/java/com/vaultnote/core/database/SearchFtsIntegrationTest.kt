package com.vaultnote.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.VaultItemType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchFtsIntegrationTest {
    private lateinit var database: VaultDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun externalContentTriggersTrackInsertUpdateAndDelete() = runBlocking {
        database.vaultItemDao().insert(noteEntity())
        val rowId = database.searchDao().insertDocument(
            SearchDocumentEntity(
                itemId = ITEM_ID,
                title = "Private receipt",
                body = "Warranty details",
                tags = "finance",
                attachmentFilenames = "",
                ocrText = "",
            ),
        )

        assertEquals(1, matchCount("receipt"))
        assertEquals(1, matchCount("r*"))

        database.searchDao().updateDocument(
            requireNotNull(database.searchDao().getDocumentForItem(ITEM_ID)).copy(
                rowId = rowId,
                title = "Travel itinerary",
            ),
        )
        assertEquals(0, matchCount("receipt"))
        assertEquals(1, matchCount("itinerary"))

        database.searchDao().deleteDocumentForItem(ITEM_ID)
        assertEquals(0, matchCount("itinerary"))
    }

    private fun matchCount(term: String): Int {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM search_fts WHERE search_fts MATCH ?",
            arrayOf(term),
        )
        return cursor.use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }

    private fun noteEntity(): VaultItemEntity = VaultItemEntity(
        id = ITEM_ID,
        type = VaultItemType.NOTE,
        title = "",
        body = "",
        ocrText = "",
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
    )

    private companion object {
        const val ITEM_ID = "00000000-0000-0000-0000-000000000001"
    }
}
