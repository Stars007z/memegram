package com.example.memegram.data.gallery

import android.webkit.MimeTypeMap
import com.example.memegram.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import androidx.core.net.toUri

actual suspend fun AttachItem.readUploadBytes(): ByteArray = withContext(Dispatchers.IO) {
    when (this@readUploadBytes) {
        is AttachItem.FromPicker -> {
            val ctx = AppContextHolder.context
            val input: InputStream = ctx.contentResolver.openInputStream(file.uri)
                ?: error("Не удалось открыть файл: ${file.name}")
            input.use { it.readBytes() }
        }

        is AttachItem.FromGallery -> {
            val ctx = AppContextHolder.context
            val input: InputStream = ctx.contentResolver.openInputStream(thumb.id.toUri())
                ?: error("Не удалось открыть файл из галереи: ${thumb.id}")
            input.use { it.readBytes() }
        }
    }
}

actual fun AttachItem.guessMimeType(): String = when (this) {
    is AttachItem.FromPicker -> {
        val ctx = AppContextHolder.context
        ctx.contentResolver.getType(file.uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase())
            ?: "image/jpeg"
    }

    is AttachItem.FromGallery -> {
        val ctx = AppContextHolder.context
        ctx.contentResolver.getType(thumb.id.toUri()) ?: "image/jpeg"
    }
}
