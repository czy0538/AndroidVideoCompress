package com.videocompress.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import com.videocompress.VideoCompressApp
import com.videocompress.core.FileHelper
import com.videocompress.core.VideoCompressor
import com.videocompress.data.db.CompressionTaskDao
import com.videocompress.data.db.TaskStatus
import com.videocompress.data.settings.SettingsRepository
import com.videocompress.util.CompressionState
import com.videocompress.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@UnstableApi
class CompressionForegroundService : Service() {

    private lateinit var dao: CompressionTaskDao
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var compressor: VideoCompressor
    private lateinit var fileHelper: FileHelper
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var wakeLock: PowerManager.WakeLock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var compressionJob: Job? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopCompression()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as VideoCompressApp
        dao = app.database.compressionTaskDao()
        settingsRepo = app.settingsRepository
        compressor = VideoCompressor(this)
        fileHelper = FileHelper(this)
        notificationHelper = NotificationHelper(this)

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoCompress::Compression")

        registerReceiver(stopReceiver, IntentFilter(NotificationHelper.ACTION_STOP), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationHelper.buildProgressNotification("Preparing...", 0, 0, 0)
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)

        if (compressionJob == null || compressionJob?.isActive != true) {
            startCompression()
        }

        return START_STICKY
    }

    private fun startCompression() {
        compressionJob = serviceScope.launch {
            wakeLock.acquire(10 * 60 * 60 * 1000L)
            try {
                dao.resetInProgressTasks()
                fileHelper.cleanupTempFiles()

                val targetBitrateBps = settingsRepo.targetBitrateMbps.first() * 1_000_000
                val targetResolution = settingsRepo.targetResolution.first()

                val unfinished = dao.getUnfinishedTasks()
                val totalCount = unfinished.size
                if (totalCount == 0) {
                    stopSelf()
                    return@launch
                }

                CompressionState.start(totalCount)
                var completedCount = 0

                while (true) {
                    val task = dao.getNextPendingTask() ?: break

                    dao.updateTaskStatus(task.id, TaskStatus.IN_PROGRESS)
                    CompressionState.updateProgress(task.displayName, 0)
                    CompressionState.updateCounts(completedCount, totalCount)

                    updateNotification(task.displayName, 0, completedCount, totalCount)

                    try {
                        val result = compressor.compress(
                            inputUri = task.videoUri,
                            targetBitrateBps = targetBitrateBps,
                            targetResolution = targetResolution,
                            originalWidth = task.originalWidth,
                            originalHeight = task.originalHeight,
                            onProgress = { progress ->
                                CompressionState.updateProgress(task.displayName, progress)
                                updateNotification(task.displayName, progress, completedCount, totalCount)
                            }
                        )

                        val replaced = fileHelper.replaceOriginalWithCompressed(
                            originalUri = task.videoUri,
                            compressedFile = result.outputFile,
                            originalDateAdded = task.originalDateAdded,
                            originalDateModified = task.originalDateModified,
                            originalPath = task.originalPath
                        )

                        if (replaced) {
                            val savedBytes = task.originalSize - result.outputSize
                            CompressionState.addSavedBytes(savedBytes)
                            dao.updateTaskStatus(task.id, TaskStatus.COMPLETED, compressedSize = result.outputSize)
                        } else {
                            dao.updateTaskStatus(task.id, TaskStatus.FAILED, error = "Failed to replace file")
                        }
                    } catch (e: Exception) {
                        dao.updateTaskStatus(task.id, TaskStatus.FAILED, error = e.message)
                    }

                    completedCount++
                    CompressionState.updateCounts(completedCount, totalCount)
                }

                val savedMb = CompressionState.savedBytes.value / (1024 * 1024)
                notificationHelper.showCompletedNotification(completedCount, savedMb)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                CompressionState.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopCompression() {
        compressionJob?.cancel()
        CompressionState.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(fileName: String, progress: Int, completed: Int, total: Int) {
        val notification = notificationHelper.buildProgressNotification(fileName, progress, completed, total)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
        serviceScope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
    }
}
