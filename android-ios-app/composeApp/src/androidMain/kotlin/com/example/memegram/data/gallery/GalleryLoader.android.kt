package com.example.memegram.data.gallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberGalleryLoader(): GalleryLoader {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    var isGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        isGranted = granted
    }

    return remember(context, isGranted) {
        object : GalleryLoader {
            override val isPermissionGranted = isGranted

            override fun requestPermission() {
                if (!isGranted) launcher.launch(permission)
            }

            override suspend fun loadRecent(limit: Int): List<GalleryThumb> = withContext(Dispatchers.IO) {
                if (!isGranted) return@withContext emptyList()
                val results    = mutableListOf<GalleryThumb>()
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                )

                context.contentResolver.query(
                    collection, projection, null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    var n = 0
                    while (cursor.moveToNext() && n < limit) {
                        val id        = cursor.getLong(idCol)
                        val name      = cursor.getString(nameCol)
                        val dateAdded = cursor.getLong(dateCol)
                        val uri       = ContentUris.withAppendedId(collection, id)
                        try {
                            val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                            else {
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id,
                                    MediaStore.Images.Thumbnails.MINI_KIND, null) ?: continue
                            }
                            ByteArrayOutputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                results += GalleryThumb(uri.toString(), out.toByteArray(), name, dateAdded)
                                n++
                            }
                        } catch (_: Exception) { }
                    }
                }
                results
            }
        }
    }
}