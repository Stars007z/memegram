package com.example.memegram.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.example.memegram.decodeToImageBitmap
import com.example.memegram.decodeToImageBitmapDownsampled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageBitmapCache {
    private const val MAX_ENTRIES = 256
    private val cache = LinkedHashMap<String, ImageBitmap>()

    fun get(key: String): ImageBitmap? {
        val v = cache.remove(key) ?: return null
        cache[key] = v
        return v
    }

    fun put(key: String, bitmap: ImageBitmap) {
        cache.remove(key)
        cache[key] = bitmap
        while (cache.size > MAX_ENTRIES) {
            val firstKey = cache.keys.firstOrNull() ?: break
            cache.remove(firstKey)
        }
    }

    fun clear() {
        cache.clear()
    }

    fun invalidatePrefix(prefix: String) {
        val it = cache.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k.startsWith(prefix)) it.remove()
        }
    }
}

private fun ByteArray.fingerprint(): String {
    val n = size
    if (n == 0) return "0"
    val a = this[0].toInt() and 0xFF
    val b = this[(n / 2)].toInt() and 0xFF
    val c = this[n - 1].toInt() and 0xFF
    return "$n.$a.$b.$c"
}

@Composable
fun rememberAsyncImageBitmap(
    bytes: ByteArray?,
    cacheKey: String? = null,
    maxDimension: Int = 0,
): ImageBitmap? {
    val effectiveKey = remember(cacheKey, maxDimension, bytes) {
        if (cacheKey == null || bytes == null) return@remember null
        val base = if (maxDimension > 0) "$cacheKey@$maxDimension" else cacheKey
        "$base#${bytes.fingerprint()}"
    }

    var bitmap by remember(effectiveKey) {
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
