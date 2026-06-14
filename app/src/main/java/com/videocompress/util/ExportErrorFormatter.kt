package com.videocompress.util

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException

@UnstableApi
object ExportErrorFormatter {
    fun format(throwable: Throwable): String {
        val exportException = throwable as? ExportException
        if (exportException == null) {
            return throwable.message ?: throwable::class.java.simpleName
        }

        return buildString {
            append(exportException.getErrorCodeName())
            append(" at ")
            append(exportException.timestampMs)
            append("ms")

            exportException.codecInfo?.let { codecInfo ->
                append("; codec=")
                append(codecInfo)
            }

            exportException.cause?.message?.let { causeMessage ->
                append("; cause=")
                append(causeMessage)
            }

            exportException.message?.let { message ->
                append("; message=")
                append(message)
            }
        }
    }
}
