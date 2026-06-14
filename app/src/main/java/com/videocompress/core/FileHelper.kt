package com.videocompress.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class ReplaceResult(
    val replaced: Boolean,
    val modifiedTimeRestored: Boolean,
    val message: String? = null
)

class FileHelper(private val context: Context) {

    suspend fun replaceOriginalWithCompressed(
        originalUri: Uri,
        compressedFile: File,
        originalDateAdded: Long,
        originalDateModified: Long,
        originalPath: String,
        originalFilePath: String
    ): ReplaceResult = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(originalUri, "wt")?.use { outputStream ->
                FileInputStream(compressedFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext ReplaceResult(false, false, "Failed to open output stream")

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, originalDateAdded)
                put(MediaStore.Video.Media.DATE_MODIFIED, originalDateModified)
            }
            context.contentResolver.update(originalUri, values, null, null)

            val filePath = when {
                originalFilePath.startsWith("/") -> originalFilePath
                originalPath.startsWith("/") -> originalPath
                else -> ""
            }

            val modifiedTimeRestored = if (filePath.isNotEmpty()) {
                val originalFile = File(filePath)
                val restored = originalFile.setLastModified(originalDateModified * 1000)
                if (!restored) {
                    Log.w(TAG, "Failed to restore modified time path=$filePath uri=$originalUri")
                }
                restored
            } else {
                Log.w(TAG, "No absolute file path available to restore modified time uri=$originalUri")
                false
            }

            ReplaceResult(true, modifiedTimeRestored)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replace original uri=$originalUri", e)
            ReplaceResult(false, false, e.message)
        }
    }

    fun deleteTempFile(file: File) {
        if (file.name.startsWith("compress_temp_") && file.name.endsWith(".mp4")) {
            file.delete()
        }
    }

    suspend fun restoreModifiedTime(
        uri: Uri,
        originalDateAdded: Long,
        originalDateModified: Long,
        originalFilePath: String,
        originalPath: String
    ): ReplaceResult = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DATE_ADDED, originalDateAdded)
                put(MediaStore.Video.Media.DATE_MODIFIED, originalDateModified)
            }
            context.contentResolver.update(uri, values, null, null)

            val filePath = findBestFilePath(uri, originalFilePath, originalPath)
            val restored = if (filePath.isNotEmpty()) {
                val file = File(filePath)
                val result = file.setLastModified(originalDateModified * 1000)
                if (!result) {
                    Log.w(TAG, "Failed to restore modified time path=$filePath uri=$uri")
                }
                result
            } else {
                Log.w(TAG, "No absolute file path available to restore modified time uri=$uri")
                false
            }

            ReplaceResult(true, restored)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore modified time uri=$uri", e)
            ReplaceResult(false, false, e.message)
        }
    }

    fun cleanupTempFiles() {
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("compress_temp_") && it.name.endsWith(".mp4")
        }?.forEach { it.delete() }
    }

    private fun findBestFilePath(uri: Uri, originalFilePath: String, originalPath: String): String {
        if (originalFilePath.startsWith("/")) return originalFilePath
        if (originalPath.startsWith("/")) return originalPath

        val projection = arrayOf(MediaStore.Video.Media.DATA)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                if (dataColumn >= 0 && cursor.moveToFirst()) {
                    cursor.getString(dataColumn).orEmpty()
                } else {
                    ""
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query file path for uri=$uri", e)
            ""
        }
    }

    private companion object {
        const val TAG = "FileHelper"
    }
}
