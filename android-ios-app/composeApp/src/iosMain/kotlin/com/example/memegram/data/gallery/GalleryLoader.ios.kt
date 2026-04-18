package com.example.memegram.data.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberGalleryLoader(): GalleryLoader = remember {
    object : GalleryLoader {
        override val isPermissionGranted = false
        override fun requestPermission() { }
        override suspend fun loadAll(): List<GalleryThumb> = emptyList()
        override suspend fun loadThumbBytes(id: String): ByteArray? = null
    }
}
