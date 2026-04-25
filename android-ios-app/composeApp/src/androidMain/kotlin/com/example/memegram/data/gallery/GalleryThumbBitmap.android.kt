package com.example.memegram.data.gallery

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import com.example.memegram.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun GalleryLoader.loadThumbBitmap(
    id: String,
    targetSizePx: Int,
): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val context = AppContextHolder.context
        val resolver = context.contentResolver
        val uri = id.toUri()
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(targetSizePx, targetSizePx), null)
        } else {
            @Suppress("DEPRECATION")
            val mediaId = ContentUris.parseId(uri)
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver,
                mediaId,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
        bmp?.asImageBitmap()
    }.getOrNull()
}
