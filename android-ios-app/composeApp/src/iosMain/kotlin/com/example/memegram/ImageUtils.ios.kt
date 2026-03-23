package com.example.memegram

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun ByteArray.decodeToImageBitmap(): ImageBitmap =
    Image.makeFromEncoded(this).toComposeImageBitmap()