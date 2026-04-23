package com.example.memegram

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * iOS image decode/crop/encode helpers.
 *
 * IMPORTANT: Skia on iOS bundles its own libjpeg-turbo headers but at runtime
 * conflicts with the system libjpeg version that gets linked in via UIKit /
 * ImageIO ("Wrong JPEG library version: library is 90, caller expects 62").
 * This makes both Image.makeFromEncoded(...) and Image.encodeToData(JPEG, ...)
 * unreliable. So we use UIKit/CoreGraphics for JPEG decode + encode and only
 * use Skia for the in-memory bitmap → ImageBitmap conversion.
 */

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(pinned.addressOf(0), this.size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = this.length.toInt()
    val out = ByteArray(len)
    if (len > 0) {
        out.usePinned { memcpy(it.addressOf(0), this.bytes, this.length) }
    }
    return out
}

/**
 * Decode a CGImage into a Skia Bitmap (RGBA8888 premul).
 */
@OptIn(ExperimentalForeignApi::class)
private fun cgImageToSkiaBitmap(cg: CGImageRef): Bitmap? {
    val w = CGImageGetWidth(cg).toInt()
    val h = CGImageGetHeight(cg).toInt()
    if (w <= 0 || h <= 0) return null

    val rowBytes = w * 4
    val pixels = ByteArray(rowBytes * h)
    val cs = CGColorSpaceCreateDeviceRGB() ?: return null
    val ok = pixels.usePinned { pinned ->
        val ctx = CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = w.convert(),
            height = h.convert(),
            bitsPerComponent = 8.convert(),
            bytesPerRow = rowBytes.convert(),
            space = cs,
            bitmapInfo = (CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big).convert()
        ) ?: return@usePinned false
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cg)
        true
    }
    if (!ok) return null

    val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val bmp = Bitmap()
    bmp.allocPixels(info)
    bmp.installPixels(pixels)
    bmp.setImmutable()
    return bmp
}

/**
 * Decode encoded image bytes (JPEG/PNG/HEIC/etc.) into a Skia Bitmap by going
 * through UIImage + CoreGraphics. Avoids Skia's libjpeg path entirely.
 */
@OptIn(ExperimentalForeignApi::class)
private fun decodeViaUIKit(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val data = bytes.toNSData()
    val ui = UIImage.imageWithData(data) ?: return null
    val cg = ui.CGImage ?: return null
    return cgImageToSkiaBitmap(cg)
}

actual fun ByteArray.decodeToImageBitmap(): ImageBitmap {
    if (isEmpty()) throw IllegalArgumentException("decodeToImageBitmap: empty bytes")
    val bmp = decodeViaUIKit(this)
        ?: throw IllegalArgumentException("decodeToImageBitmap: UIImage decode failed")
    return Image.makeFromBitmap(bmp).toComposeImageBitmap()
}

@OptIn(ExperimentalForeignApi::class)
actual fun ByteArray.decodeToImageBitmapDownsampled(maxDimension: Int): ImageBitmap? {
    if (isEmpty() || maxDimension <= 0) return null
    return runCatching {
        val data = this.toNSData()
        val ui = UIImage.imageWithData(data) ?: return@runCatching null
        val cg = ui.CGImage ?: return@runCatching null
        val srcW = CGImageGetWidth(cg).toInt()
        val srcH = CGImageGetHeight(cg).toInt()
        if (srcW <= 0 || srcH <= 0) return@runCatching null
        val longest = maxOf(srcW, srcH)
        if (longest <= maxDimension) {
            val bmp = cgImageToSkiaBitmap(cg) ?: return@runCatching null
            return@runCatching Image.makeFromBitmap(bmp).toComposeImageBitmap()
        }

        val ratio = maxDimension.toDouble() / longest.toDouble()
        val dstW = (srcW * ratio).toInt().coerceAtLeast(1)
        val dstH = (srcH * ratio).toInt().coerceAtLeast(1)

        val rowBytes = dstW * 4
        val pixels = ByteArray(rowBytes * dstH)
        val cs = CGColorSpaceCreateDeviceRGB() ?: return@runCatching null
        val ok = pixels.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = dstW.convert(),
                height = dstH.convert(),
                bitsPerComponent = 8.convert(),
                bytesPerRow = rowBytes.convert(),
                space = cs,
                bitmapInfo = (CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big).convert()
            ) ?: return@usePinned false
            CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, dstW.toDouble(), dstH.toDouble()), cg)
            true
        }
        if (!ok) return@runCatching null

        val info = ImageInfo(dstW, dstH, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap()
        bmp.allocPixels(info)
        bmp.installPixels(pixels)
        bmp.setImmutable()
        Image.makeFromBitmap(bmp).toComposeImageBitmap()
    }.getOrNull()
}

@OptIn(ExperimentalForeignApi::class)
actual fun ByteArray.cropImage(x: Int, y: Int, width: Int, height: Int): ByteArray {
    val data = this.toNSData()
    val ui = UIImage.imageWithData(data) ?: error("cropImage: UIImage decode failed")
    val cg = ui.CGImage ?: error("cropImage: no CGImage")
    val srcW = CGImageGetWidth(cg).toInt()
    val srcH = CGImageGetHeight(cg).toInt()
    val cx = x.coerceIn(0, (srcW - 1).coerceAtLeast(0))
    val cy = y.coerceIn(0, (srcH - 1).coerceAtLeast(0))
    val cw = width.coerceAtMost(srcW - cx).coerceAtLeast(1)
    val ch = height.coerceAtMost(srcH - cy).coerceAtLeast(1)

    val rowBytes = cw * 4
    val cs = CGColorSpaceCreateDeviceRGB() ?: error("cropImage: no colorspace")
    val croppedUi: UIImage = ByteArray(rowBytes * ch).usePinned { pinned ->
        val ctx = CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = cw.convert(),
            height = ch.convert(),
            bitsPerComponent = 8.convert(),
            bytesPerRow = rowBytes.convert(),
            space = cs,
            bitmapInfo = (CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big).convert()
        ) ?: error("cropImage: CGBitmapContextCreate failed")
        val drawX = -cx.toDouble()
        val drawY = -(srcH - cy - ch).toDouble()
        CGContextDrawImage(
            ctx,
            CGRectMake(drawX, drawY, srcW.toDouble(), srcH.toDouble()),
            cg
        )
        val outCg = CGBitmapContextCreateImage(ctx) ?: error("cropImage: snapshot failed")
        UIImage.imageWithCGImage(outCg)
    }

    val jpeg = UIImageJPEGRepresentation(croppedUi, 0.9)
        ?: error("cropImage: JPEG encode failed")
    return jpeg.toByteArray()
}
