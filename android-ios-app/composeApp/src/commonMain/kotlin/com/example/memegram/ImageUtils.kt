package com.example.memegram

import androidx.compose.ui.graphics.ImageBitmap

expect fun ByteArray.decodeToImageBitmap(): ImageBitmap

expect fun ByteArray.cropImage(x: Int, y: Int, width: Int, height: Int): ByteArray