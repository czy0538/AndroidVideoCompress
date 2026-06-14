package com.videocompress.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.core.VideoInfo
import com.videocompress.data.db.CompressionTask
import com.videocompress.data.db.TaskStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isCompressing by viewModel.isCompressing.collectAsStateWithLifecycle()
    val currentFileName by viewModel.currentFileName.collectAsStateWithLifecycle()
    val currentProgress by viewModel.currentProgress.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val savedBytes by viewModel.savedBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showFailureLogs by remember { mutableStateOf(false) }

    if (uiState.hasUnfinishedTasks) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnfinishedTasks() },
            title = { Text("Unfinished Tasks") },
            text = { Text("There are unfinished compression tasks. Continue?") },
            confirmButton = {
                TextButton(onClick = { viewModel.resumeUnfinishedTasks() }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUnfinishedTasks() }) {
                    Text("Discard")
                }
            }
        )
    }

    if (showFailureLogs) {
        FailureLogsDialog(
            failedTasks = uiState.failedTasks,
            formatFailureLog = viewModel::formatFailureLog,
            formatAllFailureLogs = viewModel::formatAllFailureLogs,
            onCopy = { label, text -> copyToClipboard(context, label, text) },
            onDismiss = { showFailureLogs = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VideoCompress") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (isCompressing) {
                CompressionProgressSection(
                    currentFileName = currentFileName,
                    currentProgress = currentProgress,
                    completedCount = completedCount,
                    totalCount = totalCount,
                    savedBytes = savedBytes,
                    onStop = { viewModel.stopCompression() }
                )
            } else {
                Button(
                    onClick = { viewModel.scanVideos() },
                    enabled = !uiState.isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isScanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(if (uiState.isScanning) "Scanning..." else "Scan Videos")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.videos.isNotEmpty() && !isCompressing) {
                val toCompress = uiState.videos.filter { it.needsCompression }
                val estimatedSaving = toCompress.sumOf { it.size } / 2

                Text(
                    "Found ${toCompress.size} videos to compress (${uiState.videos.size} total)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Estimated saving: ~${estimatedSaving / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.startCompression() },
                    enabled = toCompress.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Compression (${toCompress.size} videos)")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (uiState.recentTasks.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                "Recent Changes",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Showing ${uiState.recentTasks.size} of ${uiState.finishedTaskCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (uiState.failedTaskCount > 0) {
                                Button(
                                    onClick = { showFailureLogs = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("View Failure Logs (${uiState.failedTaskCount})")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Button(
                                onClick = { viewModel.repairCompletedModifiedTimes() },
                                enabled = !uiState.isRepairingModifiedTimes && !isCompressing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (uiState.isRepairingModifiedTimes) {
                                        "Repairing Modified Times..."
                                    } else {
                                        "Repair Modified Times"
                                    }
                                )
                            }
                            uiState.modifiedTimeRepairMessage?.let { message ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (uiState.canShowMoreHistory || uiState.canShowLessHistory) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (uiState.canShowMoreHistory) {
                                        Button(
                                            onClick = { viewModel.showMoreHistory() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Show More")
                                        }
                                    }
                                    if (uiState.canShowLessHistory) {
                                        TextButton(
                                            onClick = { viewModel.showLessHistory() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Show Less")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(uiState.recentTasks, key = { "task-${it.id}" }) { task ->
                        RecentTaskItem(task)
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                items(uiState.videos, key = { it.uri.toString() }) { video ->
                    VideoItem(video)
                }
            }
        }
    }
}

@Composable
private fun FailureLogsDialog(
    failedTasks: List<CompressionTask>,
    formatFailureLog: (CompressionTask) -> String,
    formatAllFailureLogs: () -> String,
    onCopy: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Failure Logs") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (failedTasks.isEmpty()) {
                    Text("No failed tasks.")
                } else {
                    failedTasks.forEach { task ->
                        val logText = formatFailureLog(task)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    task.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    task.errorMessage ?: "No error message",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = { onCopy("Failure log", logText) },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Copy")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy("Failure logs", formatAllFailureLogs()) }) {
                Text("Copy All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CompressionProgressSection(
    currentFileName: String,
    currentProgress: Int,
    completedCount: Int,
    totalCount: Int,
    savedBytes: Long,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Compressing...", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                currentFileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { currentProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$currentProgress%", style = MaterialTheme.typography.bodySmall)
                Text("$completedCount / $totalCount", style = MaterialTheme.typography.bodySmall)
            }

            if (savedBytes > 0) {
                Text(
                    "Saved: ${savedBytes / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun RecentTaskItem(task: CompressionTask) {
    val statusColor = when (task.status) {
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        TaskStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (task.status) {
        TaskStatus.COMPLETED -> "Modified"
        TaskStatus.SKIPPED -> "Skipped"
        TaskStatus.FAILED -> "Failed"
        else -> task.status.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    task.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                buildTaskDetail(task),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (task.originalPath.isNotEmpty()) {
                Text(
                    task.originalPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VideoItem(video: VideoInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = if (!video.needsCompression) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                video.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatFileSize(video.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${video.width}x${video.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${video.bitrate / 1_000_000}Mbps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!video.needsCompression) {
                Text(
                    "No compression needed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun buildTaskDetail(task: CompressionTask): String {
    return when (task.status) {
        TaskStatus.COMPLETED -> {
            val compressedSize = task.compressedSize ?: return "Original: ${formatFileSize(task.originalSize)}"
            val savedBytes = task.originalSize - compressedSize
            buildString {
                append(formatFileSize(task.originalSize))
                append(" -> ")
                append(formatFileSize(compressedSize))
                append(" | Saved ")
                append(formatFileSize(savedBytes))
                task.skippedReason?.let { reason ->
                    append(" | ")
                    append(reason)
                }
            }
        }
        TaskStatus.SKIPPED -> {
            val compressedSize = task.compressedSize
            val reason = task.skippedReason ?: "No smaller output"
            if (compressedSize != null) {
                "${formatFileSize(task.originalSize)} -> ${formatFileSize(compressedSize)} | $reason"
            } else {
                "${formatFileSize(task.originalSize)} | $reason"
            }
        }
        TaskStatus.FAILED -> task.errorMessage ?: "Compression failed"
        else -> task.status.name
    }
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024.0)
    } else {
        String.format("%.0f MB", mb)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
}
