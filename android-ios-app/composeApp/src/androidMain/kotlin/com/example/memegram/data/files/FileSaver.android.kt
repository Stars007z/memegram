package com.example.memegram.data.files

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.memegram.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

actual suspend fun saveDownloadedFile(
    bytes: ByteArray,
    fileName: String,
    mime: String
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val ctx = AppContextHolder.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Memegram")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return@withContext null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri.toString()
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloads, "Memegram").apply { if (!exists()) mkdirs() }
            val outFile = File(targetDir, fileName)
            outFile.writeBytes(bytes)
            outFile.absolutePath
        }
    }.onFailure {
        println("MemegramDebug [FileSaver]: save failed: ${it.message}")
    }.getOrNull()
}

actual suspend fun openSavedFile(pathOrUri: String, mime: String): Boolean = withContext(Dispatchers.Main) {
    runCatching {
        val ctx = AppContextHolder.context
        val uri = if (pathOrUri.startsWith("content://")) {
            pathOrUri.toUri()
        } else {
            FileProvider.getUriForFile(
                ctx,
                ctx.packageName + ".fileprovider",
                File(pathOrUri)
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    }.onFailure {
        println("MemegramDebug [FileSaver]: open failed: ${it.message}")
    }.getOrDefault(false)
}
