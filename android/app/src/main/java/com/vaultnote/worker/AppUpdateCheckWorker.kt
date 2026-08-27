package com.vaultnote.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vaultnote.R
import com.vaultnote.app.MainActivity
import com.vaultnote.app.appContainer
import com.vaultnote.core.update.AppUpdateCheckResult
import java.util.concurrent.CancellationException

class AppUpdateCheckWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val repository = applicationContext.appContainer().appUpdateRepository
            if (!repository.automaticChecksEnabled()) return Result.success()
            when (val update = repository.checkForUpdate()) {
                is AppUpdateCheckResult.Available -> {
                    if (repository.shouldNotify(update.release)) {
                        notifyAvailable(applicationContext, update.release.versionName)
                        repository.markNotified(update.release)
                    }
                    Result.success()
                }
                is AppUpdateCheckResult.Failed -> {
                    if (update.retryable && runAttemptCount < MAX_RETRIES) Result.retry()
                    else Result.success()
                }
                is AppUpdateCheckResult.Incompatible,
                AppUpdateCheckResult.UpToDate,
                -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private fun notifyAvailable(context: Context, versionName: String) {
        if (!canPostNotifications(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_notification_channel_description)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
        val openSettings = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_APP_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vaultnote_monochrome)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_notification, versionName))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            return
        }
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "vaultnote_app_updates"
        const val NOTIFICATION_ID = 0x5655
        const val MAX_RETRIES = 3
    }
}
