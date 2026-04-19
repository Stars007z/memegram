package com.example.memegram.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.example.memegram.decodeToImageBitmapDownsampled
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
    cacheKey: String? = null,
    maxDimension: Int = 0,
): ImageBitmap? {
    val effectiveKey = remember(cacheKey, maxDimension) {
        cacheKey?.let { if (maxDimension > 0) "$it@$maxDimension" else it }
    }

    var bitmap by remember(effectiveKey, bytes) {
        mutableStateOf(effectiveKey?.let { ImageBitmapCache.get(it) })
    }

    LaunchedEffect(effectiveKey, bytes) {
        if (bytes == null) {
            bitmap = null
            return@LaunchedEffect
        }
        if (effectiveKey != null) {
            ImageBitmapCache.get(effectiveKey)?.let {
                bitmap = it
                return@LaunchedEffect
            }
        }
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                if (maxDimension > 0) {
                    bytes.decodeToImageBitmapDownsampled(maxDimension)
                } else {
                    bytes.decodeToImageBitmap()
                }
            }.getOrNull()
        }
        if (decoded != null && effectiveKey != null) {
            ImageBitmapCache.put(effectiveKey, decoded)
        }
        bitmap = decoded
    }

    return bitmap
}
