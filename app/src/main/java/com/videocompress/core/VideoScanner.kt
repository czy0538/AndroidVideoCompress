package com.videocompress.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val bitrate: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long,
    val dateModified: Long,
    val path: String,
    val filePath: String,
    val needsCompression: Boolean
)

class VideoScanner(private val context: Context) {

    suspend fun scanVideos(
        minSizeBytes: Long,
        targetBitrateBps: Long,
        targetResolution: Int
    ): List<VideoInfo> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoInfo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BITRATE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATA
        )

        val selection = "${MediaStore.Video.Media.SIZE} >= ?"
        val selectionArgs = arrayOf(minSizeBytes.toString())
        val sortOrder = "${MediaStore.Video.Media.SIZE} DESC"

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val bitrateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BITRATE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = Uri.withAppendedPath(collection, id.toString())
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val mediaStoreBitrate = cursor.getLong(bitrateColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val relativePath = cursor.getString(relativePathColumn).orEmpty()
                val dataPath = cursor.getString(dataColumn).orEmpty()
                val path = if (relativePath.isNotEmpty()) relativePath + name else dataPath

                val bitrate = if (mediaStoreBitrate > 0) mediaStoreBitrate else getBitrate(uri)
                val needsCompression = shouldCompress(bitrate, width, height, targetBitrateBps, targetResolution)

                videos.add(
                    VideoInfo(
                        uri = uri,
                        displayName = name,
                        size = size,
                        bitrate = bitrate,
                        width = width,
                        height = height,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        path = path,
                        filePath = dataPath,
                        needsCompression = needsCompression
                    )
                )
            }
        }
        videos
    }

    private fun getBitrate(uri: Uri): Long {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun shouldCompress(
            bitrate: Long,
            width: Int,
            height: Int,
            targetBitrateBps: Long,
            targetResolution: Int
        ): Boolean {
            if (targetResolution == -1) {
                return bitrate > targetBitrateBps
            }
            val shortSide = minOf(width, height)
            val longSide = maxOf(width, height)
            val targetLong = when (targetResolution) {
                720 -> 1280
                1080 -> 1920
                else -> return bitrate > targetBitrateBps
            }
            val targetShort = targetResolution
            return bitrate > targetBitrateBps || longSide > targetLong || shortSide > targetShort
        }
    }
}
