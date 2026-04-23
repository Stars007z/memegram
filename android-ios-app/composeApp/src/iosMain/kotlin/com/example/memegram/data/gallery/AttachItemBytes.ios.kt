package com.example.memegram.data.gallery

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Photos.PHAsset
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeNone
import platform.Photos.PHImageRequestOptionsVersionCurrent
import platform.posix.memcpy
import kotlin.coroutines.resume

actual suspend fun AttachItem.readUploadBytes(): ByteArray = when (this) {
    is AttachItem.FromPicker  -> file.readBytes()
    is AttachItem.FromGallery -> readPhAssetBytes(thumb.id) ?: ByteArray(0)
    is AttachItem.FromBytes   -> bytes
}

actual fun AttachItem.guessMimeType(): String {
    if (this is AttachItem.FromBytes) return mime
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isNotEmpty()) {
        val m = mimeFromExtension(ext)
        if (m != "application/octet-stream") return m
    }
    if (this is AttachItem.FromGallery) return "image/jpeg"
    return "application/octet-stream"
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun readPhAssetBytes(localIdentifier: String): ByteArray? {
    val fetch = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localIdentifier), null)
    val asset = (if (fetch.count > 0u) fetch.objectAtIndex(0u) else null) as? PHAsset ?: return null
    return suspendCancellableCoroutine { cont ->
        val opts = PHImageRequestOptions().apply {
            synchronous = false
            networkAccessAllowed = true
            resizeMode = PHImageRequestOptionsResizeModeNone
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            version = PHImageRequestOptionsVersionCurrent
        }
        PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(
            asset = asset,
            options = opts
        ) { data: NSData?, dataUTI: String?, _, _ ->
            if (data == null) {
                if (cont.isActive) cont.resume(null)
                return@requestImageDataAndOrientationForAsset
            }
            val isHeic = dataUTI?.contains("heic", ignoreCase = true) == true ||
                    dataUTI?.contains("heif", ignoreCase = true) == true
            val outData: NSData = if (isHeic) {
                val ui = platform.UIKit.UIImage.imageWithData(data)
                if (ui != null) {
                    platform.UIKit.UIImageJPEGRepresentation(ui, 0.92) ?: data
                } else data
            } else data
            val bytes = ByteArray(outData.length.toInt()).apply {
                usePinned { memcpy(it.addressOf(0), outData.bytes, outData.length) }
            }
            if (cont.isActive) cont.resume(bytes)
        }
    }
}

private fun mimeFromExtension(ext: String): String = when (ext) {
    "jpg", "jpeg" -> "image/jpeg"
    "png"         -> "image/png"
    "gif"         -> "image/gif"
    "webp"        -> "image/webp"
    "heic", "heif" -> "image/heic"
    "bmp"         -> "image/bmp"
    "svg"         -> "image/svg+xml"
    "mp4", "m4v"  -> "video/mp4"
    "mov"         -> "video/quicktime"
    "avi"         -> "video/x-msvideo"
    "mkv"         -> "video/x-matroska"
    "webm"        -> "video/webm"
    "mp3"         -> "audio/mpeg"
    "m4a", "aac"  -> "audio/mp4"
    "wav"         -> "audio/wav"
    "ogg", "opus" -> "audio/ogg"
    "flac"        -> "audio/flac"
    "pdf"         -> "application/pdf"
    "doc"         -> "application/msword"
    "docx"        -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls"         -> "application/vnd.ms-excel"
    "xlsx"        -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt"         -> "application/vnd.ms-powerpoint"
    "pptx"        -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "txt", "log"  -> "text/plain"
    "json"        -> "application/json"
    "xml"         -> "application/xml"
    "csv"         -> "text/csv"
    "html", "htm" -> "text/html"
    "zip"         -> "application/zip"
    "rar"         -> "application/vnd.rar"
    "7z"          -> "application/x-7z-compressed"
    "tar"         -> "application/x-tar"
    "gz"          -> "application/gzip"
    else          -> "application/octet-stream"
}
