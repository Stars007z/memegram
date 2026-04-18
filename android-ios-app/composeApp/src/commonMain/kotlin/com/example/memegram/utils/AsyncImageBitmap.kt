package com.example.memegram.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


object ImageBitmapCache {
    private const val MAX_ENTRIES = 64
    private val cache = LinkedHashMap<String, ImageBitmap>(16, 0.75f)

    fun get(key: String): ImageBitmap? = cache[key]

    fun put(key: String, bitmap: ImageBitmap) {
        cache[key] = bitmap
        while (cache.size > MAX_ENTRIES) {
            val firstKey = cache.keys.firstOrNull() ?: break
            cache.remove(firstKey)
        }
    }

    fun clear() {
        cache.clear()
    }
}

@Composable
fun rememberAsyncImageBitmap(
    bytes: ByteArray?,
    cacheKey: String? = null
): ImageBitmap? {
    var bitmap by remember(cacheKey, bytes) {
        mutableStateOf(cacheKey?.let { ImageBitmapCache.get(it) })
    }

    LaunchedEffect(cacheKey, bytes) {
        if (bytes == null) {
            bitmap = null
            return@LaunchedEffect
        }
        if (cacheKey != null) {
            ImageBitmapCache.get(cacheKey)?.let {
                bitmap = it
                return@LaunchedEffect
            }
        }
        val decoded = withContext(Dispatchers.Default) {
            runCatching { bytes.decodeToImageBitmap() }.getOrNull()
        }
        if (decoded != null && cacheKey != null) {
            ImageBitmapCache.put(cacheKey, decoded)
        }
        bitmap = decoded
    }

    return bitmap
}
