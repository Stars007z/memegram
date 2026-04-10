package com.example.memegram.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum

@OptIn(ExperimentalForeignApi::class)
actual fun saveImageToGallery(bytes: ByteArray, filename: String): Boolean {
    return try {
        val nsData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val image = UIImage(data = nsData) ?: return false
        UIImageWriteToSavedPhotosAlbum(image, null, null, null)
        true
    } catch (e: Exception) {
        println("MemegramDebug [GallerySaver]: Failed to save image: ${e.message}")
        false
    }
}
