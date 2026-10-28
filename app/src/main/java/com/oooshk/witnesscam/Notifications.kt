package com.oooshk.witnesscam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val RECORDING_ID = 1
    private const val DRIVE_ID = 2
    private const val CH_RECORDING = "recording"
    private const val CH_ALERTS = "alerts"

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun recording(context: Context, pendingUploads: Int): Notification {
        ensureChannels(context)
        // Stopping goes through the PIN screen (it stops immediately if no PIN is set).
        val stop = PendingIntent.getActivity(
            context, 1, PinActivity.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (pendingUploads == 0) {
            context.getString(R.string.notif_all_uploaded)
        } else {
            context.resources.getQuantityString(R.plurals.notif_pending, pendingUploads, pendingUploads)
        }
        return NotificationCompat.Builder(context, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_recording))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stop, context.getString(R.string.stop), stop)
            .build()
    }

    fun updateRecording(context: Context, pendingUploads: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            .notify(RECORDING_ID, recording(context, pendingUploads))
    }

    fun driveNeedsAuth(context: Context) {
        ensureChannels(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_drive_title))
            .setContentText(context.getString(R.string.notif_drive_text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(DRIVE_ID, n)
    }
}
