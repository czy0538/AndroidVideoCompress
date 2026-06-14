package com.videocompress.core

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.videocompress.util.ExportErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CompressionResult(
    val outputFile: File,
    val outputSize: Long
)

@UnstableApi
class VideoCompressor(private val context: Context) {

    suspend fun compress(
        inputUri: Uri,
        targetBitrateBps: Int,
        targetResolution: Int,
        originalWidth: Int,
        originalHeight: Int,
        onProgress: (Int) -> Unit
    ): CompressionResult = withContext(Dispatchers.Main) {
        val outputFile = File(context.cacheDir, "compress_temp_${System.currentTimeMillis()}.mp4")

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrateBps)
                    .build()
            )
            .build()

        val videoEffects = buildVideoEffects(targetResolution, originalWidth, originalHeight)

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoderFactory)
            .setMaxDelayBetweenMuxerSamplesMs(MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS)
            .build()

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        suspendCancellableCoroutine { continuation ->
            val progressHolder = ProgressHolder()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val progressRunnable = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress)
                    }
                    handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(progressRunnable, 500)

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(progressRunnable)
                    val result = CompressionResult(
                        outputFile = outputFile,
                        outputSize = outputFile.length()
                    )
                    Log.i(
                        TAG,
                        "Export completed uri=$inputUri output=${outputFile.absolutePath} " +
                            "size=${result.outputSize} targetBitrate=$targetBitrateBps " +
                            "targetResolution=$targetResolution input=${originalWidth}x$originalHeight"
                    )
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacks(progressRunnable)
                    Log.e(
                        TAG,
                        "Export failed uri=$inputUri output=${outputFile.absolutePath} " +
                            "targetBitrate=$targetBitrateBps targetResolution=$targetResolution " +
                            "input=${originalWidth}x$originalHeight " +
                            "error=${ExportErrorFormatter.format(exportException)}",
                        exportException
                    )
                    outputFile.delete()
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            })

            transformer.start(editedMediaItem, outputFile.absolutePath)

            continuation.invokeOnCancellation {
                handler.removeCallbacks(progressRunnable)
                transformer.cancel()
                outputFile.delete()
            }
        }
    }

    private fun buildVideoEffects(
        targetResolution: Int,
        originalWidth: Int,
        originalHeight: Int
    ): List<androidx.media3.common.Effect> {
        if (targetResolution == -1) return emptyList()

        val shortSide = minOf(originalWidth, originalHeight)
        val longSide = maxOf(originalWidth, originalHeight)
        val targetShort = targetResolution
        val targetLong = when (targetResolution) {
            720 -> 1280
            1080 -> 1920
            else -> return emptyList()
        }

        if (longSide <= targetLong && shortSide <= targetShort) return emptyList()

        val isPortrait = originalHeight > originalWidth
        val (w, h) = if (isPortrait) targetShort to targetLong else targetLong to targetShort

        return listOf(
            Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        )
    }

    private companion object {
        const val TAG = "VideoCompressor"
        const val MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS = 120_000L
    }
}
