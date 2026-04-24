package com.example.memegram

import com.example.memegram.data.files.deleteAvatarBytes
import com.example.memegram.data.files.readAvatarBytes
import com.example.memegram.data.files.writeAvatarBytes
import com.example.memegram.data.network.ApiService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AvatarCache(private val api: ApiService) {

    private val memory = mutableMapOf<String, ByteArray>()
    private val failed = mutableSetOf<String>()
    private val perKeyMutex = mutableMapOf<String, Mutex>()
    private val mutexGuard = Mutex()

    fun getCached(mediaId: String): ByteArray? = memory[mediaId]

    suspend fun load(mediaId: String): ByteArray? {
        memory[mediaId]?.let { return it }
        if (mediaId in failed) return null

        val lock = mutexGuard.withLock { perKeyMutex.getOrPut(mediaId) { Mutex() } }
        return lock.withLock {
            memory[mediaId]?.let { return@withLock it }

            // Disk first.
            readAvatarBytes(mediaId)?.let { bytes ->
                memory[mediaId] = bytes
                return@withLock bytes
            }

            try {
                val info = api.getItemDownloadUrl(mediaId)
                val bytes = api.downloadBytesFromUrl(info.downloadUrl)
                memory[mediaId] = bytes
                writeAvatarBytes(mediaId, bytes)
                bytes
            } catch (e: Exception) {
                println("AvatarCache: Failed to load $mediaId: ${e.message}")
                failed.add(mediaId)
                null
            }
        }
    }

    suspend fun invalidate(mediaId: String) {
        memory.remove(mediaId)
        failed.remove(mediaId)
        deleteAvatarBytes(mediaId)
    }

    fun clear() {
        memory.clear()
        failed.clear()
    }
}
