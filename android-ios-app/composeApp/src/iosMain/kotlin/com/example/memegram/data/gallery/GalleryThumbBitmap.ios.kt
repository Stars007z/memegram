package com.example.memegram.data.gallery

import androidx.compose.ui.graphics.ImageBitmap
import com.example.memegram.decodeToImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Photos.PHAsset
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeOpportunistic
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
actual suspend fun GalleryLoader.loadThumbBitmap(
    id: String,
    targetSizePx: Int,
): ImageBitmap? {
    val bytes = withContext(Dispatchers.Default) {
        runCatching { fetchJpegBytes(id, targetSizePx) }.getOrNull()
    } ?: return null
    return withContext(Dispatchers.Default) {
        runCatching { bytes.decodeToImageBitmap() }.getOrNull()
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun fetchJpegBytes(id: String, targetSizePx: Int): ByteArray? {
    val fetch = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null)
    val asset = (if (fetch.count > 0u) fetch.objectAtIndex(0u) else null) as? PHAsset ?: return null
    return suspendCancellableCoroutine { cont ->
        val opts = PHImageRequestOptions().apply {
            synchronous = false
            networkAccessAllowed = true
            resizeMode = PHImageRequestOptionsResizeModeFast
            deliveryMode = PHImageRequestOptionsDeliveryModeOpportunistic
        }
        var resumed = false
        PHImageManager.defaultManager().requestImageForAsset(
            asset = asset,
            targetSize = CGSizeMake(targetSizePx.toDouble(), targetSizePx.toDouble()),
            contentMode = PHImageContentModeAspectFill,
            options = opts,
        ) { image: UIImage?, _ ->
            if (resumed || !cont.isActive) return@requestImageForAsset
            if (image == null) {
                resumed = true
                cont.resume(null)
                return@requestImageForAsset
            }
            val data: NSData? = UIImageJPEGRepresentation(image, 0.8)
            if (data == null) {
                resumed = true
                cont.resume(null)
                return@requestImageForAsset
            }
            val bytes = ByteArray(data.length.toInt()).apply {
                usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
            }
            resumed = true
            cont.resume(bytes)
        }
    }
}
