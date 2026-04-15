package com.example.memegram

import com.example.memegram.data.network.ApiService

class AvatarCache(private val api: ApiService) {

    private val cache = mutableMapOf<String, ByteArray>()
    private val failed = mutableSetOf<String>()

    fun getCached(mediaId: String): ByteArray? = cache[mediaId]

    suspend fun load(mediaId: String): ByteArray? {
        cache[mediaId]?.let { return it }
        if (mediaId in failed) return null

        return try {
            val info = api.getItemDownloadUrl(mediaId)
            val bytes = api.downloadBytesFromUrl(info.downloadUrl)
            cache[mediaId] = bytes
            bytes
        } catch (e: Exception) {
            println("AvatarCache: Failed to load $mediaId: ${e.message}")
            failed.add(mediaId)
            null
        }
    }

    fun clear() {
        cache.clear()
        failed.clear()
    }
}
