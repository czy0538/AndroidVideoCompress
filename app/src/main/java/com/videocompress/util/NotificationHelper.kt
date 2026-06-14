package com.videocompress.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.videocompress.MainActivity

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "video_compression"
        const val NOTIFICATION_ID = 1
        const val COMPLETED_NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.videocompress.ACTION_STOP"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video Compression",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Video compression progress"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildProgressNotification(
        fileName: String,
        currentProgress: Int,
        completedCount: Int,
        totalCount: Int
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("Compressing: $fileName")
            .setContentText("Progress: $completedCount/$totalCount")
            .setProgress(100, currentProgress, false)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun buildCompletedNotification(totalCompressed: Int, savedMb: Long): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Compression Complete")
            .setContentText("Compressed $totalCompressed videos, saved ${savedMb}MB")
            .setAutoCancel(true)
            .build()
    }

    fun showCompletedNotification(totalCompressed: Int, savedMb: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(COMPLETED_NOTIFICATION_ID, buildCompletedNotification(totalCompressed, savedMb))
    }
}
