package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.vaultnote.core.database.entity.DatedEntryAlertEntity
import com.vaultnote.core.database.entity.DatedEntryEntity
import com.vaultnote.core.database.model.AgendaEntryRow
import com.vaultnote.core.database.model.DatedEntryWithAlerts
import kotlinx.coroutines.flow.Flow

@Dao
interface DatedEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: DatedEntryEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateEntry(entry: DatedEntryEntity): Int

    @Query("SELECT * FROM dated_entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntry(entryId: String): DatedEntryEntity?

    @Transaction
    @Query("SELECT * FROM dated_entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntryWithAlerts(entryId: String): DatedEntryWithAlerts?

    @Query("DELETE FROM dated_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: String): Int

    @Query("DELETE FROM dated_entries WHERE item_id = :itemId")
    suspend fun deleteEntriesForItem(itemId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlerts(alerts: List<DatedEntryAlertEntity>)

    @Query("DELETE FROM dated_entry_alerts WHERE entry_id = :entryId")
    suspend fun deleteAlertsForEntry(entryId: String): Int

    @Query("SELECT * FROM dated_entry_alerts WHERE id = :alertId LIMIT 1")
    suspend fun getAlert(alertId: String): DatedEntryAlertEntity?

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateAlert(alert: DatedEntryAlertEntity): Int

    @Transaction
    @Query(
        """
        SELECT * FROM dated_entries
        WHERE item_id = :itemId
        ORDER BY completed_at IS NOT NULL ASC, occurrence_at ASC, id ASC
        """,
    )
    fun observeForItem(itemId: String): Flow<List<DatedEntryWithAlerts>>

    @Transaction
    @Query(
        """
        SELECT * FROM dated_entries
        WHERE item_id = :itemId
        ORDER BY completed_at IS NOT NULL ASC, occurrence_at ASC, id ASC
        """,
    )
    suspend fun getForItem(itemId: String): List<DatedEntryWithAlerts>

    @Transaction
    @Query(
        """
        SELECT * FROM dated_entries
        WHERE completed_at IS NULL
        ORDER BY occurrence_at ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun getActiveEntries(limit: Int): List<DatedEntryWithAlerts>

    @Transaction
    @Query(
        """
        SELECT
            dated_entries.*,
            vault_items.title AS note_title,
            vault_items.color AS note_color,
            vault_items.is_archived AS note_is_archived
        FROM dated_entries
        INNER JOIN vault_items ON vault_items.id = dated_entries.item_id
        WHERE vault_items.deleted_at IS NULL
          AND (:includeCompleted = 1 OR dated_entries.completed_at IS NULL)
        ORDER BY dated_entries.occurrence_at ASC, dated_entries.id ASC
        LIMIT :limit
        """,
    )
    fun observeAgenda(includeCompleted: Boolean, limit: Int): Flow<List<AgendaEntryRow>>

    @Query(
        """
        UPDATE dated_entry_alerts
        SET snoozed_until = :snoozedUntil
        WHERE id = :alertId
        """,
    )
    suspend fun setSnoozedUntil(alertId: String, snoozedUntil: Long?): Int

    @Query(
        """
        UPDATE dated_entry_alerts
        SET last_delivered_occurrence = :occurrenceAt,
            snoozed_until = NULL
        WHERE id = :alertId
        """,
    )
    suspend fun markDelivered(alertId: String, occurrenceAt: Long): Int
}
