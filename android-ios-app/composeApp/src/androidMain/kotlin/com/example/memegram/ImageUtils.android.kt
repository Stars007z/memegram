package com.example.memegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale

actual fun ByteArray.decodeToImageBitmap(): ImageBitmap =
    BitmapFactory.decodeByteArray(this, 0, size).asImageBitmap()

actual fun ByteArray.decodeToImageBitmapDownsampled(maxDimension: Int): ImageBitmap? {
    if (isEmpty() || maxDimension <= 0) return null
    return runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(this, 0, size, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return@runCatching null

        var sample = 1
        val longest = maxOf(srcW, srcH)
        while ((longest / (sample * 2)) >= maxDimension) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = BitmapFactory.decodeByteArray(this, 0, size, opts)
            ?: return@runCatching null

        val scaledW = sampled.width
        val scaledH = sampled.height
        val longestSampled = maxOf(scaledW, scaledH)
        val finalBitmap = if (longestSampled > maxDimension) {
            val ratio = maxDimension.toFloat() / longestSampled.toFloat()
            val newW = (scaledW * ratio).toInt().coerceAtLeast(1)
            val newH = (scaledH * ratio).toInt().coerceAtLeast(1)
            val scaled = sampled.scale(newW, newH)
            if (scaled !== sampled) sampled.recycle()
            scaled
        } else sampled

        finalBitmap.asImageBitmap()
    }.getOrNull()
}

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