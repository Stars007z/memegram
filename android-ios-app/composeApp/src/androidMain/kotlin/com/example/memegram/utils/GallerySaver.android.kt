package com.example.memegram.utils

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.memegram.AppContextHolder

actual fun saveImageToGallery(bytes: ByteArray, filename: String): Boolean {
    return try {
        val context = AppContextHolder.context
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Memegram")
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        true
    } catch (e: Exception) {
        println("MemegramDebug [GallerySaver]: Failed to save image: ${e.message}")
        false
    }
}
