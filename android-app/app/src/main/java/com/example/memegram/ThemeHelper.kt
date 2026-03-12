package com.example.memegram

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import kotlin.math.max
import kotlin.math.min

object ThemeHelper {
    private const val PREFS_NAME = "ThemePrefs"
    const val KEY_TOPBAR_COLOR = "topbar_color"
    const val KEY_TOPBAR_IMAGE = "topbar_image"
    const val KEY_CHAT_BG_COLOR = "chat_bg_color"
    const val KEY_CHAT_BG_IMAGE = "chat_bg_image"
    const val KEY_MY_BUBBLE_COLOR = "my_bubble_color"
    const val KEY_MY_BUBBLE_IMAGE = "my_bubble_image"
    const val KEY_THEIR_BUBBLE_COLOR = "their_bubble_color"
    const val KEY_THEIR_BUBBLE_IMAGE = "their_bubble_image"

    // DEFAULT_STATUS_BAR удален
    const val DEFAULT_TOPBAR = "#D1D1D6"
    const val DEFAULT_CHAT_BG = "#FFFFFF"
    const val DEFAULT_MY_BUBBLE = "#007AFF"
    const val DEFAULT_THEIR_BUBBLE = "#E5E5EA"

    fun saveColor(context: Context, key: String, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(key, color)
        }
    }

    fun getColor(context: Context, key: String, defaultColorHex: String): Int {
        val defaultColor = defaultColorHex.toColorInt()
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, defaultColor)
    }

    fun getColor(context: Context, key: String, defaultColor: Int): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, defaultColor)
    }

    fun saveImageUri(context: Context, key: String, uriString: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(key, uriString)
        }
    }

    fun getImageUri(context: Context, key: String): String? {
        val uri = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
        return if (uri.isNullOrEmpty()) null else uri
    }

    // Обновленная логика для автоматического переключения
    fun applyStatusBarColor(activity: Activity) {
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        val isNightMode = (activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Если ночь - фон черный, иконки светлые (isAppearanceLightStatusBars = false)
        // Если день - фон белый, иконки темные (isAppearanceLightStatusBars = true)
        window.statusBarColor = if (isNightMode) Color.BLACK else Color.WHITE
        insetsController.isAppearanceLightStatusBars = !isNightMode
    }

    fun applyBackground(
        context: Context,
        view: View?,
        colorKey: String,
        imageKey: String,
        defaultColorHex: String
    ) {
        if (view == null) return
        val imageUri = getImageUri(context, imageKey)

        if (imageUri != null) {
            try {
                val uri = imageUri.toUri()
                val scaledBitmap = loadScaledBitmap(context, uri, view)
                if (scaledBitmap != null) {
                    val drawable = scaledBitmap.toDrawable(context.resources).apply {
                        gravity = android.view.Gravity.FILL
                    }
                    view.background = drawable
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        view.setBackgroundColor(getColor(context, colorKey, defaultColorHex))
    }

    private fun loadScaledBitmap(context: Context, uri: Uri, view: View): Bitmap? {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            val imageWidth = options.outWidth
            val imageHeight = options.outHeight

            val viewWidth = if (view.width > 0) view.width else view.measuredWidth
            val viewHeight = if (view.height > 0) view.height else view.measuredHeight

            val targetWidth = if (viewWidth > 0) viewWidth else 1080
            val targetHeight = if (viewHeight > 0) viewHeight else 1920

            var sampleSize = 1

            if (imageHeight > targetHeight || imageWidth > targetWidth) {
                val halfHeight = imageHeight / 2
                val halfWidth = imageWidth / 2

                while (halfHeight / sampleSize >= targetHeight &&
                    halfWidth / sampleSize >= targetWidth) {
                    sampleSize *= 2
                }
            }

            val decodedBitmap = BitmapFactory.Options().run {
                inSampleSize = sampleSize
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, this)
                }
            } ?: return null

            return scaleBitmapCenterCrop(decodedBitmap, targetWidth, targetHeight)

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun scaleBitmapCenterCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceWidth = source.width
        val sourceHeight = source.height
        val scaleX = targetWidth.toFloat() / sourceWidth
        val scaleY = targetHeight.toFloat() / sourceHeight
        val scale = max(scaleX, scaleY)

        val scaledWidth = (sourceWidth * scale).toInt()
        val scaledHeight = (sourceHeight * scale).toInt()

        val scaledBitmap = source.scale(scaledWidth, scaledHeight)

        val startX = max(0, (scaledWidth - targetWidth) / 2)
        val startY = max(0, (scaledHeight - targetHeight) / 2)

        val finalWidth = min(targetWidth, scaledWidth)
        val finalHeight = min(targetHeight, scaledHeight)

        val croppedBitmap = Bitmap.createBitmap(scaledBitmap, startX, startY, finalWidth, finalHeight)

        if (scaledBitmap != croppedBitmap) {
            scaledBitmap.recycle()
        }
        if (source != croppedBitmap) {
            source.recycle()
        }
        return croppedBitmap
    }
}
