package com.videocompress.data.db

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "compression_tasks")
data class CompressionTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoUriString: String,
    val displayName: String,
    val originalSize: Long,
    val originalBitrate: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalDateAdded: Long,
    val originalDateModified: Long,
    val originalPath: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val errorMessage: String? = null,
    val compressedSize: Long? = null,
    val batchId: String
) {
    val videoUri: Uri get() = Uri.parse(videoUriString)
}
