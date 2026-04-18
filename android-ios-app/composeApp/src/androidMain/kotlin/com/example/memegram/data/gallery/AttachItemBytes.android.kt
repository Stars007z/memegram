package com.example.memegram.data.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.memegram.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import androidx.core.net.toUri

actual suspend fun AttachItem.readUploadBytes(): ByteArray = withContext(Dispatchers.IO) {
    val ctx = AppContextHolder.context
    val (uri, mime) = when (this@readUploadBytes) {
        is AttachItem.FromPicker  -> file.uri to (ctx.contentResolver.getType(file.uri) ?: "")
        is AttachItem.FromGallery -> thumb.id.toUri() to (ctx.contentResolver.getType(thumb.id.toUri()) ?: "")
    }

    val raw: ByteArray = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Не удалось открыть файл: $uri")

    if (this@readUploadBytes is AttachItem.FromPicker && this@readUploadBytes.asFile) return@withContext raw
    if (!mime.startsWith("image/")) return@withContext raw

    runCatching { normalizeOrientation(raw) }.getOrDefault(raw)
}

private fun normalizeOrientation(jpegBytes: ByteArray): ByteArray {
    val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
    val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )
    if (orientation == ExifInterface.ORIENTATION_NORMAL ||
        orientation == ExifInterface.ORIENTATION_UNDEFINED
    ) return jpegBytes

    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f);  postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            else -> return jpegBytes
        }
    }

    val src = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
    val rotated = try {
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    } catch (_: OutOfMemoryError) {
        return jpegBytes
    }
    if (rotated !== src) src.recycle()

    val out = ByteArrayOutputStream(jpegBytes.size)
    rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
    rotated.recycle()
    return out.toByteArray()
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
