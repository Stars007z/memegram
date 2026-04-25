package com.example.memegram.data.gallery

import androidx.compose.ui.graphics.ImageBitmap

expect suspend fun GalleryLoader.loadThumbBitmap(
    id: String,
    targetSizePx: Int = 256,
): ImageBitmap?
