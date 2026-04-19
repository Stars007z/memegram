package com.example.memegram

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

actual fun ByteArray.decodeToImageBitmap(): ImageBitmap =
    Image.makeFromEncoded(this).toComposeImageBitmap()

actual fun ByteArray.decodeToImageBitmapDownsampled(maxDimension: Int): ImageBitmap? {
    if (isEmpty() || maxDimension <= 0) return null
    return runCatching {
        val image = Image.makeFromEncoded(this)
        val srcW = image.width
        val srcH = image.height
        val longest = maxOf(srcW, srcH)
        if (longest <= maxDimension) return@runCatching image.toComposeImageBitmap()

        val ratio = maxDimension.toFloat() / longest.toFloat()
        val dstW = (srcW * ratio).toInt().coerceAtLeast(1)
        val dstH = (srcH * ratio).toInt().coerceAtLeast(1)

        val info = ImageInfo(dstW, dstH, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap()
        bmp.allocPixels(info)
        val canvas = Canvas(bmp)
        val src = Rect.makeXYWH(0f, 0f, srcW.toFloat(), srcH.toFloat())
        val dst = Rect.makeXYWH(0f, 0f, dstW.toFloat(), dstH.toFloat())
        canvas.drawImageRect(image, src, dst, SamplingMode.LINEAR, Paint(), true)
        bmp.setImmutable()
        Image.makeFromBitmap(bmp).toComposeImageBitmap()
    }.getOrNull()
}

actual fun ByteArray.cropImage(x: Int, y: Int, width: Int, height: Int): ByteArray {
    val image = Image.makeFromEncoded(this)
    val bitmap = Bitmap.makeFromImage(image)
    val cx = x.coerceIn(0, bitmap.width - 1)
    val cy = y.coerceIn(0, bitmap.height - 1)
    val cw = width.coerceAtMost(bitmap.width - cx)
    val ch = height.coerceAtMost(bitmap.height - cy)

    val croppedInfo = ImageInfo(cw, ch, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val croppedBitmap = Bitmap()
    croppedBitmap.allocPixels(croppedInfo)

    val srcRowBytes = bitmap.rowBytes
    val srcPixels = bitmap.readPixels(bitmap.imageInfo, bitmap.rowBytes)!!
    val dstPixels = ByteArray(cw * 4 * ch)

    for (row in 0 until ch) {
        val srcOffset = ((cy + row) * bitmap.width + cx) * 4
        val dstOffset = row * cw * 4
        srcPixels.copyInto(dstPixels, dstOffset, srcOffset, srcOffset + cw * 4)
    }
    croppedBitmap.installPixels(dstPixels)

    val croppedImage = Image.makeFromBitmap(croppedBitmap)
    val encoded = croppedImage.encodeToData() ?: error("Failed to encode cropped image")
    return encoded.bytes
}