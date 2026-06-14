package com.videocompress.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CompressionState {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _currentFileName = MutableStateFlow("")
    val currentFileName: StateFlow<String> = _currentFileName

    private val _currentProgress = MutableStateFlow(0)
    val currentProgress: StateFlow<Int> = _currentProgress

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount

    private val _savedBytes = MutableStateFlow(0L)
    val savedBytes: StateFlow<Long> = _savedBytes

    fun updateProgress(fileName: String, progress: Int) {
        _currentFileName.value = fileName
        _currentProgress.value = progress
    }

    fun updateCounts(completed: Int, total: Int) {
        _completedCount.value = completed
        _totalCount.value = total
    }

    fun addSavedBytes(bytes: Long) {
        _savedBytes.value += bytes
    }

    fun start(total: Int) {
        _isRunning.value = true
        _totalCount.value = total
        _completedCount.value = 0
        _currentProgress.value = 0
        _savedBytes.value = 0
    }

    fun stop() {
        _isRunning.value = false
        _currentFileName.value = ""
        _currentProgress.value = 0
    }
}
