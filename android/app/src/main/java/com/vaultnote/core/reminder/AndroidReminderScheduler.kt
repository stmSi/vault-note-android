package com.vaultnote.core.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.vaultnote.core.database.VaultDatabase

class AndroidReminderScheduler(
    context: Context,
    private val database: VaultDatabase,
) : ReminderScheduler {
    private val applicationContext = context.applicationContext
    private val alarmManager =
        applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val datedEntryDao = database.datedEntryDao()
    private val itemDao = database.vaultItemDao()

    override suspend fun reconcileEntry(entryId: String) {
        val row = datedEntryDao.getEntryWithAlerts(entryId) ?: return
        row.alerts.forEach { alert ->
            val operation = alarmPendingIntent(alert.id, entryId, row.entry.itemId)
            alarmManager.cancel(operation)
            val item = itemDao.getById(row.entry.itemId)
            if (
                item == null ||
                item.deletedAt != null ||
                row.entry.completedAt != null ||
                (
                    alert.lastDeliveredOccurrence == row.entry.occurrenceAt &&
                        alert.snoozedUntil == null
                    )
            ) {
                return@forEach
            }
            val requestedAt = alert.snoozedUntil ?: (
                row.entry.occurrenceAt - alert.leadTimeMinutes * MILLIS_PER_MINUTE
                )
            val triggerAt = maxOf(requestedAt, System.currentTimeMillis() + MINIMUM_DELAY_MILLIS)
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation,
                )
            }
        }
    }

    override suspend fun cancelEntry(entryId: String) {
        datedEntryDao.getEntryWithAlerts(entryId)?.let { row ->
            row.alerts.forEach { alert ->
                alarmManager.cancel(alarmPendingIntent(alert.id, entryId, row.entry.itemId))
            }
        }
    }

    override suspend fun reconcileAll() {
        datedEntryDao.getActiveEntries(MAX_RECONCILED_ENTRIES).forEach { row ->
            reconcileEntry(row.entry.id)
        }
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun alarmPendingIntent(
        alertId: String,
        entryId: String,
        itemId: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        0,
        Intent(applicationContext, ReminderAlarmReceiver::class.java)
            .setAction(ReminderAlarmReceiver.ACTION_DELIVER)
            .setData(Uri.parse("vaultnote://reminder/alert/$alertId"))
            .putExtra(ReminderAlarmReceiver.EXTRA_ALERT_ID, alertId)
            .putExtra(ReminderAlarmReceiver.EXTRA_ENTRY_ID, entryId)
            .putExtra(ReminderAlarmReceiver.EXTRA_ITEM_ID, itemId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MINIMUM_DELAY_MILLIS = 1_000L
        const val MAX_RECONCILED_ENTRIES = 10_000
    }
}
