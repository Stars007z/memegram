package com.example.memegram.data.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.darwin.NSObject

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

private val docInteractionRetainer = mutableListOf<UIDocumentInteractionController>()

actual suspend fun openSavedFile(pathOrUri: String, mime: String): Boolean = withContext(Dispatchers.Main) {
    runCatching {
        val url = NSURL.fileURLWithPath(pathOrUri)
        val controller = UIDocumentInteractionController.interactionControllerWithURL(url)
        docInteractionRetainer.add(controller)
        val rootVc = UIApplication.sharedApplication.keyWindow?.rootViewController
            ?: return@runCatching false
        controller.presentPreviewAnimated(true)
        true
    }.onFailure {
        println("MemegramDebug [FileSaver]: open failed: ${it.message}")
    }.getOrDefault(false)
}
