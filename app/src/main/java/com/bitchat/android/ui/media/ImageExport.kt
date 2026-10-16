package com.bitchat.android.ui.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException

/** Export only on explicit user action. Callers perform all storage work off the main thread. */
internal object ImageExport {
    fun documentIntent(source: File): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType(source)
        putExtra(Intent.EXTRA_TITLE, source.name)
    }

    private fun mimeType(source: File): String = when (source.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    @WorkerThread
    @RequiresApi(Build.VERSION_CODES.Q)
    fun toDownloads(resolver: ContentResolver, source: File): Boolean {
        var destination: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType(source))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("No export destination")
            copy(resolver, source, destination)
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (resolver.update(destination, published, null, null) != 1) {
                throw IOException("Export could not be published")
            }
            true
        } catch (_: Exception) {
            destination?.let { discard(resolver, it) }
            false
        }
    }

    @WorkerThread
    fun toDocument(resolver: ContentResolver, source: File, destination: Uri): Boolean = try {
        copy(resolver, source, destination)
        true
    } catch (_: Exception) {
        try { DocumentsContract.deleteDocument(resolver, destination) } catch (_: Exception) { }
        false
    }

    private fun copy(resolver: ContentResolver, source: File, destination: Uri) {
        source.inputStream().use { input ->
            val output = resolver.openOutputStream(destination, "w") ?: throw IOException("No output stream")
            output.use { input.copyTo(it) }
        }
    }

    private fun discard(resolver: ContentResolver, destination: Uri) {
        // The provider may reject deletion; never hide the original export failure.
        try { resolver.delete(destination, null, null) } catch (_: Exception) { }
    }
}
