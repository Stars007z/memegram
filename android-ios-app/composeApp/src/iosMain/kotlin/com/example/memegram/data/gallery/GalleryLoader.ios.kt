package com.example.memegram.data.gallery

import androidx.compose.runtime.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSinceDate
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHCachingImageManager
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberGalleryLoader(): GalleryLoader {
    var status by remember { mutableStateOf(PHPhotoLibrary.authorizationStatus()) }
    val granted = status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited

    return remember(granted) {
        object : GalleryLoader {
            override val isPermissionGranted: Boolean = granted

            private var cachedFetch: platform.Photos.PHFetchResult? = null
            private val fetchLock = kotlinx.coroutines.sync.Mutex()

            private suspend fun ensureFetch(): platform.Photos.PHFetchResult? {
                if (!granted) return null
                fetchLock.lock()
                try {
                    cachedFetch?.let { return it }
                    val opts = PHFetchOptions().apply {
                        sortDescriptors = listOf(
                            platform.Foundation.NSSortDescriptor.sortDescriptorWithKey(
                                "creationDate", ascending = false
                            )
                        )
                    }
                    val fr = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, opts)
                    cachedFetch = fr
                    return fr
                } finally {
                    fetchLock.unlock()
                }
            }

            override fun requestPermission() {
                if (granted) return
                PHPhotoLibrary.requestAuthorization { newStatus ->
                    status = newStatus
                }
            }

            override suspend fun loadAll(): List<GalleryThumb> = withContext(Dispatchers.Default) {
                val fetchResult = ensureFetch() ?: return@withContext emptyList()
                val out = ArrayList<GalleryThumb>(fetchResult.count.toInt())
                for (i in 0 until fetchResult.count.toInt()) {
                    val asset = fetchResult.objectAtIndex(i.toULong()) as? PHAsset ?: continue
                    val date: NSDate? = asset.creationDate
                    val secs  = if (date != null) {
                        val ref = date.timeIntervalSinceDate(NSDate.dateWithTimeIntervalSince1970(0.0))
                        ref.toLong()
                    } else 0L
                    val name  = asset.localIdentifier
                    out += GalleryThumb(
                        id        = asset.localIdentifier,
                        bytes     = EMPTY_BYTES,
                        name      = name,
                        dateAdded = secs
                    )
                }
                out
            }

            override suspend fun loadPage(offset: Int, limit: Int): List<GalleryThumb> = withContext(Dispatchers.Default) {
                val fetchResult = ensureFetch() ?: return@withContext emptyList()
                val total = fetchResult.count.toInt()
                if (offset >= total) return@withContext emptyList()
                val end = (offset + limit).coerceAtMost(total)
                val out = ArrayList<GalleryThumb>(end - offset)
                for (i in offset until end) {
                    val asset = fetchResult.objectAtIndex(i.toULong()) as? PHAsset ?: continue
                    val date: NSDate? = asset.creationDate
                    val secs  = if (date != null) {
                        val ref = date.timeIntervalSinceDate(NSDate.dateWithTimeIntervalSince1970(0.0))
                        ref.toLong()
                    } else 0L
                    out += GalleryThumb(
                        id        = asset.localIdentifier,
                        bytes     = EMPTY_BYTES,
                        name      = asset.localIdentifier,
                        dateAdded = secs
                    )
                }
                out
            }

            override suspend fun totalCount(): Int = withContext(Dispatchers.Default) {
                ensureFetch()?.count?.toInt() ?: 0
            }

            override suspend fun loadThumbBytes(id: String): ByteArray? {
                val fetch = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null)
                val asset = (if (fetch.count > 0u) fetch.objectAtIndex(0u) else null) as? PHAsset ?: return null
                return suspendCancellableCoroutine { cont ->
                    val opts = PHImageRequestOptions().apply {
                        synchronous     = false
                        networkAccessAllowed = true
                        resizeMode      = PHImageRequestOptionsResizeModeFast
                        deliveryMode    = PHImageRequestOptionsDeliveryModeHighQualityFormat
                    }
                    PHImageManager.defaultManager().requestImageForAsset(
                        asset = asset,
                        targetSize = CGSizeMake(256.0, 256.0),
                        contentMode = PHImageContentModeAspectFill,
                        options = opts
                    ) { image: UIImage?, _ ->
                        if (image == null) {
                            if (cont.isActive) cont.resume(null)
                            return@requestImageForAsset
                        }
                        val data: NSData? = UIImageJPEGRepresentation(image, 0.8)
                        if (data == null) {
                            if (cont.isActive) cont.resume(null)
                            return@requestImageForAsset
                        }
                        val bytes = ByteArray(data.length.toInt()).apply {
                            usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
                        }
                        if (cont.isActive) cont.resume(bytes)
                    }
                }
            }
        }
    }
}

private val EMPTY_BYTES = ByteArray(0)
