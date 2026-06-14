package com.videocompress.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videocompress.VideoCompressApp
import com.videocompress.core.FileHelper
import com.videocompress.core.VideoInfo
import com.videocompress.core.VideoScanner
import com.videocompress.data.db.CompressionTask
import com.videocompress.data.db.TaskStatus
import com.videocompress.data.settings.SettingsRepository
import com.videocompress.service.CompressionForegroundService
import com.videocompress.util.CompressionState
import com.videocompress.util.NotificationHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class MainUiState(
    val videos: List<VideoInfo> = emptyList(),
    val isScanning: Boolean = false,
    val hasUnfinishedTasks: Boolean = false,
    val recentTasks: List<CompressionTask> = emptyList(),
    val historyLimit: Int = MainViewModel.DEFAULT_HISTORY_LIMIT,
    val finishedTaskCount: Int = 0,
    val failedTasks: List<CompressionTask> = emptyList(),
    val isRepairingModifiedTimes: Boolean = false,
    val modifiedTimeRepairMessage: String? = null
) {
    val canShowMoreHistory: Boolean get() = recentTasks.size < finishedTaskCount
    val canShowLessHistory: Boolean get() = historyLimit > MainViewModel.DEFAULT_HISTORY_LIMIT
    val failedTaskCount: Int get() = failedTasks.size
}

sealed class MainEvent {
    data class RequestWritePermission(val pendingIntent: PendingIntent, val uris: List<Uri>) : MainEvent()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VideoCompressApp
    private val dao = app.database.compressionTaskDao()
    private val settingsRepo = app.settingsRepository
    private val scanner = VideoScanner(application)
    private val fileHelper = FileHelper(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState
    private val historyLimit = MutableStateFlow(DEFAULT_HISTORY_LIMIT)

    private val _events = MutableSharedFlow<MainEvent>()
    val events = _events.asSharedFlow()

    val isCompressing = CompressionState.isRunning
    val currentFileName = CompressionState.currentFileName
    val currentProgress = CompressionState.currentProgress
    val completedCount = CompressionState.completedCount
    val totalCount = CompressionState.totalCount
    val savedBytes = CompressionState.savedBytes

    val targetBitrate = settingsRepo.targetBitrateMbps.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_BITRATE_MBPS
    )
    val targetResolution = settingsRepo.targetResolution.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_RESOLUTION
    )
    val minFileSize = settingsRepo.minFileSizeMb.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_MIN_SIZE_MB
    )

    init {
        checkUnfinishedTasks()
        observeRecentTasks()
    }

    private fun checkUnfinishedTasks() {
        viewModelScope.launch {
            val count = dao.getUnfinishedCount()
            _uiState.value = _uiState.value.copy(hasUnfinishedTasks = count > 0)
        }
    }

    private fun observeRecentTasks() {
        viewModelScope.launch {
            historyLimit.collectLatest { limit ->
                dao.getRecentFinishedTasks(limit).collect { tasks ->
                    _uiState.value = _uiState.value.copy(
                        recentTasks = tasks,
                        historyLimit = limit
                    )
                }
            }
        }

        viewModelScope.launch {
            dao.getFinishedTaskCount().collect { count ->
                _uiState.value = _uiState.value.copy(finishedTaskCount = count)
            }
        }

        viewModelScope.launch {
            dao.getFailedTasks().collect { tasks ->
                _uiState.value = _uiState.value.copy(failedTasks = tasks)
            }
        }
    }

    fun scanVideos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            try {
                val minSizeBytes = settingsRepo.minFileSizeMb.first() * 1024 * 1024
                val targetBitrateBps = settingsRepo.targetBitrateMbps.first() * 1_000_000L
                val targetRes = settingsRepo.targetResolution.first()

                val videos = scanner.scanVideos(minSizeBytes, targetBitrateBps, targetRes)
                _uiState.value = _uiState.value.copy(videos = videos)
            } finally {
                _uiState.value = _uiState.value.copy(isScanning = false)
            }
        }
    }

    fun startCompression() {
        viewModelScope.launch {
            val videosToCompress = _uiState.value.videos.filter { it.needsCompression }
            if (videosToCompress.isEmpty()) return@launch

            val uris = videosToCompress.map { it.uri }
            val pendingIntent = MediaStore.createWriteRequest(
                getApplication<VideoCompressApp>().contentResolver,
                uris
            )
            _events.emit(MainEvent.RequestWritePermission(pendingIntent, uris))
        }
    }

    fun onWritePermissionGranted() {
        viewModelScope.launch {
            val videosToCompress = _uiState.value.videos.filter { it.needsCompression }
            if (videosToCompress.isEmpty()) return@launch

            val batchId = UUID.randomUUID().toString()
            val tasks = videosToCompress.map { video ->
                CompressionTask(
                    videoUriString = video.uri.toString(),
                    displayName = video.displayName,
                    originalSize = video.size,
                    originalBitrate = video.bitrate,
                    originalWidth = video.width,
                    originalHeight = video.height,
                    originalDateAdded = video.dateAdded,
                    originalDateModified = video.dateModified,
                    originalPath = video.path,
                    originalFilePath = video.filePath,
                    batchId = batchId
                )
            }
            dao.insertAll(tasks)

            val context = getApplication<VideoCompressApp>()
            val intent = Intent(context, CompressionForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }

    fun resumeUnfinishedTasks() {
        viewModelScope.launch {
            val context = getApplication<VideoCompressApp>()
            val intent = Intent(context, CompressionForegroundService::class.java)
            context.startForegroundService(intent)
            _uiState.value = _uiState.value.copy(hasUnfinishedTasks = false)
        }
    }

    fun dismissUnfinishedTasks() {
        viewModelScope.launch {
            val batchId = dao.getActiveBatchId()
            if (batchId != null) {
                dao.deleteTasksByBatch(batchId)
            }
            _uiState.value = _uiState.value.copy(hasUnfinishedTasks = false)
        }
    }

    fun stopCompression() {
        val context = getApplication<VideoCompressApp>()
        val intent = Intent(NotificationHelper.ACTION_STOP).setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    fun repairCompletedModifiedTimes() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRepairingModifiedTimes = true,
                modifiedTimeRepairMessage = null
            )

            val completedTasks = dao.getCompletedTasks()
            var restoredCount = 0
            var metadataOnlyCount = 0
            var failedCount = 0

            completedTasks.forEach { task ->
                val result = fileHelper.restoreModifiedTime(
                    uri = task.videoUri,
                    originalDateAdded = task.originalDateAdded,
                    originalDateModified = task.originalDateModified,
                    originalFilePath = task.originalFilePath,
                    originalPath = task.originalPath
                )

                when {
                    result.replaced && result.modifiedTimeRestored -> restoredCount++
                    result.replaced -> metadataOnlyCount++
                    else -> failedCount++
                }
            }

            _uiState.value = _uiState.value.copy(
                isRepairingModifiedTimes = false,
                modifiedTimeRepairMessage = "Modified time repair: $restoredCount restored, " +
                    "$metadataOnlyCount metadata-only, $failedCount failed"
            )
        }
    }

    fun showMoreHistory() {
        historyLimit.value = (historyLimit.value + HISTORY_PAGE_SIZE).coerceAtMost(MAX_HISTORY_LIMIT)
    }

    fun showLessHistory() {
        historyLimit.value = DEFAULT_HISTORY_LIMIT
    }

    fun formatFailureLog(task: CompressionTask): String {
        return buildString {
            appendLine("File: ${task.displayName}")
            appendLine("Status: ${task.status}")
            appendLine("URI: ${task.videoUriString}")
            appendLine("Path: ${task.originalPath}")
            appendLine("File path: ${task.originalFilePath}")
            appendLine("Original size: ${task.originalSize}")
            appendLine("Compressed size: ${task.compressedSize ?: "N/A"}")
            appendLine("Original bitrate: ${task.originalBitrate}")
            appendLine("Original resolution: ${task.originalWidth}x${task.originalHeight}")
            appendLine("Original date added: ${task.originalDateAdded}")
            appendLine("Original date modified: ${task.originalDateModified}")
            appendLine("Completed at: ${task.completedAt ?: "N/A"}")
            appendLine("Error:")
            appendLine(task.errorMessage ?: "No error message")
        }
    }

    fun formatAllFailureLogs(): String {
        return _uiState.value.failedTasks.joinToString(separator = "\n\n---\n\n") { task ->
            formatFailureLog(task)
        }
    }

    fun updateTargetBitrate(mbps: Int) {
        viewModelScope.launch { settingsRepo.setTargetBitrateMbps(mbps) }
    }

    fun updateTargetResolution(resolution: Int) {
        viewModelScope.launch { settingsRepo.setTargetResolution(resolution) }
    }

    fun updateMinFileSize(mb: Long) {
        viewModelScope.launch { settingsRepo.setMinFileSizeMb(mb) }
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 20
        private const val HISTORY_PAGE_SIZE = 50
        private const val MAX_HISTORY_LIMIT = 1_000
    }
}
