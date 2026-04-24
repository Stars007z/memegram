package com.example.memegram.data.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
private fun avatarsDirPath(): String {
    val fm = NSFileManager.defaultManager
    val urls = fm.URLsForDirectory(NSCachesDirectory, NSUserDomainMask)
    val baseUrl = urls.firstOrNull() as? NSURL
        ?: error("Cannot resolve Caches directory")
    val basePath = baseUrl.path ?: error("Caches URL has no path")
    val path = "$basePath/avatars"
    fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    return path
}

private fun safeName(mediaId: String): String =
    mediaId.replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun pathFor(mediaId: String): String = "${avatarsDirPath()}/${safeName(mediaId)}"

actual fun getAvatarsCacheDir(): String = avatarsDirPath()

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readAvatarBytes(mediaId: String): ByteArray? = withContext(Dispatchers.Default) {
    runCatching {
        val path = pathFor(mediaId)
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(path)) return@runCatching null
        val data = NSData.dataWithContentsOfFile(path) ?: return@runCatching null
        val length = data.length.toInt()
        if (length == 0) return@runCatching ByteArray(0)
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, length.toULong())
        }
        bytes
    }.getOrNull()
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun writeAvatarBytes(mediaId: String, bytes: ByteArray) {
    withContext(Dispatchers.Default) {
        runCatching {
            val path = pathFor(mediaId)
            val data = bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
            data.writeToFile(path, atomically = true)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun deleteAvatarBytes(mediaId: String) {
    withContext(Dispatchers.Default) {
        runCatching {
            NSFileManager.defaultManager.removeItemAtPath(pathFor(mediaId), error = null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun clearAvatarsCache() {
    withContext(Dispatchers.Default) {
        runCatching {
            val fm = NSFileManager.defaultManager
            val dir = avatarsDirPath()
            val items = fm.contentsOfDirectoryAtPath(dir, error = null) ?: return@runCatching
            for (any in items) {
                val name = any as? String ?: continue
                fm.removeItemAtPath("$dir/$name", error = null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun avatarsCacheSizeBytes(): Long = withContext(Dispatchers.Default) {
    runCatching {
        val fm = NSFileManager.defaultManager
        val dir = avatarsDirPath()
        val items = fm.contentsOfDirectoryAtPath(dir, error = null) ?: return@runCatching 0L
        var total = 0L
        for (any in items) {
            val name = any as? String ?: continue
            val attrs = fm.attributesOfItemAtPath("$dir/$name", error = null) ?: continue
            val size = attrs["NSFileSize"] as? Number ?: continue
            total += size.toLong()
        }
        total
    }.getOrDefault(0L)
}
