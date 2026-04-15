package com.example.memegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

actual fun ByteArray.decodeToImageBitmap(): ImageBitmap =
    BitmapFactory.decodeByteArray(this, 0, size).asImageBitmap()

actual fun ByteArray.cropImage(x: Int, y: Int, width: Int, height: Int): ByteArray {
    val original = BitmapFactory.decodeByteArray(this, 0, size)
    val cx = x.coerceIn(0, original.width - 1)
    val cy = y.coerceIn(0, original.height - 1)
    val cw = width.coerceAtMost(original.width - cx)
    val ch = height.coerceAtMost(original.height - cy)
    val cropped = Bitmap.createBitmap(original, cx, cy, cw, ch)
    val stream = ByteArrayOutputStream()
    cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    return stream.toByteArray()
}