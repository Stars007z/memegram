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
import androidx.core.net.toUri
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

            override suspend fun loadAll(): List<GalleryThumb> = withContext(Dispatchers.IO) {
                if (!isGranted) return@withContext emptyList()
                val results    = ArrayList<GalleryThumb>(1024)
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
                    while (cursor.moveToNext()) {
                        val id        = cursor.getLong(idCol)
                        val name      = cursor.getString(nameCol) ?: id.toString()
                        val dateAdded = cursor.getLong(dateCol)
                        val uri       = ContentUris.withAppendedId(collection, id)
                        results += GalleryThumb(
                            id        = uri.toString(),
                            bytes     = EMPTY_BYTES,
                            name      = name,
                            dateAdded = dateAdded
                        )
                    }
                }
                results
            }

            override suspend fun loadThumbBytes(id: String): ByteArray? = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = id.toUri()
                    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                    } else {
                        @Suppress("DEPRECATION")
                        val mediaId = ContentUris.parseId(uri)
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver, mediaId,
                            MediaStore.Images.Thumbnails.MINI_KIND, null
                        ) ?: return@runCatching null
                    }
                    ByteArrayOutputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        bmp.recycle()
                        out.toByteArray()
                    }
                }.getOrNull()
            }
        }
    }
}

private val EMPTY_BYTES = ByteArray(0)
