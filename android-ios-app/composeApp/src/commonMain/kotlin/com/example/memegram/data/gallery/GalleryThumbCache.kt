package com.example.memegram.data.gallery

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal object GalleryThumbCache {
    private const val MAX_ENTRIES = 384
    private val cache = LinkedHashMap<String, ImageBitmap>()

    fun get(id: String): ImageBitmap? {
        val v = cache.remove(id) ?: return null
        cache[id] = v
        return v
    }

    fun put(id: String, bitmap: ImageBitmap) {
        cache.remove(id)
        cache[id] = bitmap
        while (cache.size > MAX_ENTRIES) {
            val firstKey = cache.keys.iterator().next()
            cache.remove(firstKey)
        }
    }

    fun clear() {
        cache.clear()
    }
}

internal val GalleryDecodeSemaphore = Semaphore(permits = 4)

internal suspend inline fun <T> withGalleryDecodePermit(crossinline block: suspend () -> T): T =
    GalleryDecodeSemaphore.withPermit { block() }
