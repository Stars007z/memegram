package com.example.memegram

import com.example.memegram.data.files.deleteAvatarBytes
import com.example.memegram.data.files.readAvatarBytes
import com.example.memegram.data.files.writeAvatarBytes
import com.example.memegram.data.network.ApiService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class AvatarCache(private val api: ApiService) {

    private val memory = mutableMapOf<String, ByteArray>()
    private val failed = mutableMapOf<String, Long>()
    private val perKeyMutex = mutableMapOf<String, Mutex>()
    private val mutexGuard = Mutex()
    private val failedRetryMs = 60_000L

    fun getCached(mediaId: String): ByteArray? = memory[mediaId]

    suspend fun load(mediaId: String): ByteArray? {
        memory[mediaId]?.let { return it }
        val now = Clock.System.now().toEpochMilliseconds()
        val failedAt = failed[mediaId]
        if (failedAt != null && now - failedAt < failedRetryMs) return null

        val lock = mutexGuard.withLock { perKeyMutex.getOrPut(mediaId) { Mutex() } }
        return lock.withLock {
            memory[mediaId]?.let { return@withLock it }

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
                failed[mediaId] = Clock.System.now().toEpochMilliseconds()
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
