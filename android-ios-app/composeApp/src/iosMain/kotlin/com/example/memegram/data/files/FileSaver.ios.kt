package com.example.memegram.data.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.memegram.translation.IosFileOpenBridge
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
actual suspend fun saveDownloadedFile(
    bytes: ByteArray,
    fileName: String,
    mime: String
): String? = withContext(Dispatchers.Default) {
    runCatching {
        val docs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        ).firstOrNull() as? String ?: return@runCatching null
        val dir = "$docs/Memegram"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, true, null, null
        )
        val safeName = fileName.ifBlank { "file_${kotlin.random.Random.nextInt()}" }
        val path = "$dir/$safeName"
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        if (data.writeToFile(path, true)) path else null
    }.onFailure {
        println("MemegramDebug [FileSaver]: save failed: ${it.message}")
    }.getOrNull()
}

actual suspend fun openSavedFile(pathOrUri: String, mime: String): Boolean = withContext(Dispatchers.Main) {
    val opener = IosFileOpenBridge.delegate
    if (opener == null) {
        println("MemegramDebug [FileSaver]: FileOpenBridge not registered")
        return@withContext false
    }
    runCatching {
        opener.open(pathOrUri, mime)
    }.onFailure {
        println("MemegramDebug [FileSaver]: open failed: ${it.message}")
    }.getOrDefault(false)
}
