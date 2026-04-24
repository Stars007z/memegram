package com.example.memegram.data.files

import com.example.memegram.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun avatarsDir(): File {
    val dir = File(AppContextHolder.context.cacheDir, "avatars")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun safeName(mediaId: String): String =
    mediaId.replace(Regex("[^A-Za-z0-9._-]"), "_")

actual fun getAvatarsCacheDir(): String = avatarsDir().absolutePath

actual suspend fun readAvatarBytes(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val f = File(avatarsDir(), safeName(mediaId))
        if (f.exists() && f.isFile) {
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        } else null
    }.getOrNull()
}

actual suspend fun writeAvatarBytes(mediaId: String, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        runCatching {
            val f = File(avatarsDir(), safeName(mediaId))
            f.writeBytes(bytes)
        }
    }
}

actual suspend fun deleteAvatarBytes(mediaId: String) {
    withContext(Dispatchers.IO) {
        runCatching { File(avatarsDir(), safeName(mediaId)).delete() }
    }
}

actual suspend fun clearAvatarsCache() {
    withContext(Dispatchers.IO) {
        runCatching {
            avatarsDir().listFiles()?.forEach { it.delete() }
        }
    }
}

actual suspend fun avatarsCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
    runCatching {
        avatarsDir().listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)
}
