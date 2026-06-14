package com.videocompress.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.videocompress.VideoCompressApp
import com.videocompress.core.FileHelper
import com.videocompress.core.VideoCompressor
import com.videocompress.data.db.CompressionTaskDao
import com.videocompress.data.db.TaskStatus
import com.videocompress.data.settings.SettingsRepository
import com.videocompress.util.CompressionState
import com.videocompress.util.ExportErrorFormatter
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
    private var lastNotificationAt = 0L
    private var lastNotificationProgress = -1

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

                val totalCount = dao.getUnfinishedCount()
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

                    resetNotificationThrottle()
                    updateNotification(task.displayName, 0, completedCount, totalCount)

                    try {
                        val result = compressor.compress(
                            inputUri = task.videoUri,
                            targetBitrateBps = targetBitrateBps,
                            targetResolution = targetResolution,
                            originalWidth = task.originalWidth,
                            originalHeight = task.originalHeight,
                            onProgress = { progress ->
                                updateProgress(task.displayName, progress, completedCount, totalCount)
                            }
                        )

                        if (result.outputSize < task.originalSize) {
                            val replaceResult = fileHelper.replaceOriginalWithCompressed(
                                originalUri = task.videoUri,
                                compressedFile = result.outputFile,
                                originalDateAdded = task.originalDateAdded,
                                originalDateModified = task.originalDateModified,
                                originalPath = task.originalPath,
                                originalFilePath = task.originalFilePath
                            )
                            fileHelper.deleteTempFile(result.outputFile)
                            if (!replaceResult.replaced) {
                                dao.updateTaskStatus(
                                    task.id,
                                    TaskStatus.FAILED,
                                    error = replaceResult.message ?: "Failed to replace file",
                                    completedAt = System.currentTimeMillis()
                                )
                                completedCount++
                                CompressionState.updateCounts(completedCount, totalCount)
                                continue
                            }

                            val savedBytes = task.originalSize - result.outputSize
                            CompressionState.addSavedBytes(savedBytes)
                            dao.updateTaskStatus(
                                task.id,
                                TaskStatus.COMPLETED,
                                compressedSize = result.outputSize,
                                completedAt = System.currentTimeMillis(),
                                skippedReason = if (replaceResult.modifiedTimeRestored) {
                                    null
                                } else {
                                    "Modified time could not be restored"
                                }
                            )
                        } else {
                            fileHelper.deleteTempFile(result.outputFile)
                            dao.updateTaskStatus(
                                task.id,
                                TaskStatus.SKIPPED,
                                compressedSize = result.outputSize,
                                completedAt = System.currentTimeMillis(),
                                skippedReason = "Compressed output was not smaller"
                            )
                        }
                    } catch (e: Exception) {
                        val errorMessage = buildCompressionErrorMessage(
                            fileName = task.displayName,
                            uri = task.videoUriString,
                            originalSize = task.originalSize,
                            originalBitrate = task.originalBitrate,
                            originalWidth = task.originalWidth,
                            originalHeight = task.originalHeight,
                            targetBitrateBps = targetBitrateBps,
                            targetResolution = targetResolution,
                            throwable = e
                        )
                        Log.e(TAG, errorMessage, e)
                        dao.updateTaskStatus(
                            task.id,
                            TaskStatus.FAILED,
                            error = errorMessage,
                            completedAt = System.currentTimeMillis()
                        )
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

    private fun updateProgress(fileName: String, progress: Int, completed: Int, total: Int) {
        val boundedProgress = progress.coerceIn(0, 100)
        if (boundedProgress != CompressionState.currentProgress.value) {
            CompressionState.updateProgress(fileName, boundedProgress)
        }

        val now = System.currentTimeMillis()
        val progressChanged = boundedProgress != lastNotificationProgress
        val shouldNotify = boundedProgress == 100 ||
            lastNotificationProgress == -1 ||
            now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS

        if (progressChanged && shouldNotify) {
            updateNotification(fileName, boundedProgress, completed, total)
        }
    }

    private fun updateNotification(fileName: String, progress: Int, completed: Int, total: Int) {
        val boundedProgress = progress.coerceIn(0, 100)
        val notification = notificationHelper.buildProgressNotification(fileName, boundedProgress, completed, total)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NotificationHelper.NOTIFICATION_ID, notification)
        lastNotificationAt = System.currentTimeMillis()
        lastNotificationProgress = boundedProgress
    }

    private fun resetNotificationThrottle() {
        lastNotificationAt = 0L
        lastNotificationProgress = -1
    }

    private fun buildCompressionErrorMessage(
        fileName: String,
        uri: String,
        originalSize: Long,
        originalBitrate: Long,
        originalWidth: Int,
        originalHeight: Int,
        targetBitrateBps: Int,
        targetResolution: Int,
        throwable: Throwable
    ): String {
        return buildString {
            append("Compression failed")
            append("; file=")
            append(fileName)
            append("; uri=")
            append(uri)
            append("; originalSize=")
            append(originalSize)
            append("; originalBitrate=")
            append(originalBitrate)
            append("; originalResolution=")
            append(originalWidth)
            append("x")
            append(originalHeight)
            append("; targetBitrate=")
            append(targetBitrateBps)
            append("; targetResolution=")
            append(targetResolution)
            append("; error=")
            append(ExportErrorFormatter.format(throwable))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
        serviceScope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
    }

    private companion object {
        const val TAG = "CompressionService"
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
    }
}
