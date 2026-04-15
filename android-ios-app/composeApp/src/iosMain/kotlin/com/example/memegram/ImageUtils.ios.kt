package com.example.memegram

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun ByteArray.decodeToImageBitmap(): ImageBitmap =
    Image.makeFromEncoded(this).toComposeImageBitmap()

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