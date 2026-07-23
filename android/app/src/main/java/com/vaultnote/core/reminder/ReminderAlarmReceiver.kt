package com.vaultnote.core.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vaultnote.R
import com.vaultnote.app.MainActivity
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.DatedEntryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val applicationContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_DELIVER -> deliver(applicationContext, intent)
                    ACTION_SNOOZE -> snooze(applicationContext, intent)
                    ACTION_COMPLETE -> complete(applicationContext, intent)
                    ACTION_DISMISS -> dismiss(applicationContext, intent)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun deliver(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val row = context.appContainer().databaseForReminders
            .datedEntryDao()
            .getEntryWithAlerts(entryId) ?: return
        val alert = row.alerts.firstOrNull { it.id == alertId } ?: return
        if (row.entry.completedAt != null) return
        if (alert.lastDeliveredOccurrence == row.entry.occurrenceAt && alert.snoozedUntil == null) {
            return
        }
        if (!canPostNotifications(context)) return
        createNotificationChannel(context)
        val notificationId = alertId.hashCode()
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_REMINDER)
                .putExtra(MainActivity.EXTRA_REMINDER_ITEM_ID, itemId)
                .putExtra(MainActivity.EXTRA_REMINDER_ENTRY_ID, entryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozeIntent = actionIntent(
            context,
            ACTION_SNOOZE,
            alertId,
            entryId,
            itemId,
            notificationId,
        )
        val completionAction = if (row.entry.type == DatedEntryType.IMPORTANT_DATE) {
            ACTION_DISMISS
        } else {
            ACTION_COMPLETE
        }
        val completeIntent = actionIntent(
            context,
            completionAction,
            alertId,
            entryId,
            itemId,
            notificationId,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vaultnote_monochrome)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(context.getString(R.string.reminder_notification_private_text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_vaultnote_monochrome)
                    .setContentTitle(context.getString(R.string.reminder_notification_title))
                    .setContentText(context.getString(R.string.reminder_notification_private_text))
                    .build(),
            )
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.reminder_snooze_ten_minutes), snoozeIntent)
            .addAction(
                0,
                context.getString(
                    if (completionAction == ACTION_COMPLETE) {
                        R.string.reminder_mark_done
                    } else {
                        R.string.dismiss
                    },
                ),
                completeIntent,
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            return
        }
        context.appContainer().vaultRepository.markDatedEntryAlertDelivered(
            alertId = alertId,
            occurrenceAtEpochMillis = row.entry.occurrenceAt,
        )
    }

    private suspend fun snooze(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, alertId.hashCode())
        NotificationManagerCompat.from(context).cancel(notificationId)
        context.appContainer().vaultRepository.snoozeDatedEntryAlert(
            alertId,
            System.currentTimeMillis() + TEN_MINUTES_MILLIS,
        )
    }

    private suspend fun complete(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return
        dismissNotification(context, intent)
        context.appContainer().vaultRepository.completeDatedEntry(entryId)
    }

    private fun dismiss(context: Context, intent: Intent) {
        dismissNotification(context, intent)
    }

    private fun dismissNotification(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return
        NotificationManagerCompat.from(context).cancel(
            intent.getIntExtra(EXTRA_NOTIFICATION_ID, alertId.hashCode()),
        )
    }

    private fun actionIntent(
        context: Context,
        action: String,
        alertId: String,
        entryId: String,
        itemId: String,
        notificationId: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("vaultnote://reminder/action/$action/$alertId"))
            .putExtra(EXTRA_ALERT_ID, alertId)
            .putExtra(EXTRA_ENTRY_ID, entryId)
            .putExtra(EXTRA_ITEM_ID, itemId)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_notification_channel_description)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_DELIVER = "com.vaultnote.action.DELIVER_REMINDER"
        const val ACTION_SNOOZE = "com.vaultnote.action.SNOOZE_REMINDER"
        const val ACTION_COMPLETE = "com.vaultnote.action.COMPLETE_REMINDER"
        const val ACTION_DISMISS = "com.vaultnote.action.DISMISS_REMINDER"
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val CHANNEL_ID = "vaultnote_reminders"
        private const val TEN_MINUTES_MILLIS = 10L * 60L * 1_000L
    }
}
