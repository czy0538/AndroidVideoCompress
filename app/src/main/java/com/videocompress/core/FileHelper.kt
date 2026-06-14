package com.videocompress.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class FileHelper(private val context: Context) {

    suspend fun replaceOriginalWithCompressed(
        originalUri: Uri,
        compressedFile: File,
        originalDateAdded: Long,
        originalDateModified: Long,
        originalPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(originalUri, "wt")?.use { outputStream ->
                FileInputStream(compressedFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_MODIFIED, originalDateModified)
            }
            context.contentResolver.update(originalUri, values, null, null)

            if (originalPath.isNotEmpty()) {
                try {
                    val originalFile = File(originalPath)
                    originalFile.setLastModified(originalDateModified * 1000)
                } catch (_: Exception) {
                }
            }

            compressedFile.delete()
            true
        } catch (e: Exception) {
            compressedFile.delete()
            false
        }
    }

    fun cleanupTempFiles() {
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("compress_temp_") && it.name.endsWith(".mp4")
        }?.forEach { it.delete() }
    }
}
