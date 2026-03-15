package com.example.memegram

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri

class AppearanceActivity : BaseActivity() {

    private var currentImageTargetKey: String = ""
    private var currentImagePreviewView: View? = null
    private var currentMiniPreviewView: View? = null

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = result.data?.data
            if (imageUri != null) {
                val cropDialog = ImageCropDialog(
                    imageUri,
                    9f,
                    16f
                ) { croppedUri ->
                    ThemeHelper.saveImageUri(this, currentImageTargetKey, croppedUri.toString())
                    applyImageToView(currentImagePreviewView, croppedUri)
                    applyImageToView(currentMiniPreviewView, croppedUri)
                    Toast.makeText(this, "Image saved!", Toast.LENGTH_SHORT).show()
                }
                cropDialog.show(supportFragmentManager, "CropDialog")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)
        applyWindowInsets(R.id.mainLayout)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val previewTopBar = findViewById<View>(R.id.previewTopBar)
        val previewChatBg = findViewById<View>(R.id.previewContainer)
        val previewMyBubble = findViewById<View>(R.id.tvPreviewMessageOutgoing)
        val previewTheirBubble = findViewById<View>(R.id.tvPreviewMessageIncoming)


        // Top Bar
        setupItem(
            itemId = R.id.itemTopBar,
            title = "Top Bar",
            prefColorKey = ThemeHelper.KEY_TOPBAR_COLOR,
            prefImageKey = "NONE",
            defaultColorHex = ThemeHelper.DEFAULT_TOPBAR,
            previewView = previewTopBar
        )

        // Chat Background
        setupItem(
            itemId = R.id.itemChatBg,
            title = "Chat Background",
            prefColorKey = ThemeHelper.KEY_CHAT_BG_COLOR,
            prefImageKey = ThemeHelper.KEY_CHAT_BG_IMAGE,
            defaultColorHex = ThemeHelper.DEFAULT_CHAT_BG,
            previewView = previewChatBg
        )

        // My Messages
        setupItem(
            itemId = R.id.itemMyMessage,
            title = "My Messages",
            prefColorKey = ThemeHelper.KEY_MY_BUBBLE_COLOR,
            prefImageKey = "NONE",
            defaultColorHex = ThemeHelper.DEFAULT_MY_BUBBLE,
            previewView = previewMyBubble,
            isBubble = true
        )

        // Their Messages
        setupItem(
            itemId = R.id.itemTheirMessage,
            title = "Their Messages",
            prefColorKey = ThemeHelper.KEY_THEIR_BUBBLE_COLOR,
            prefImageKey = "NONE",
            defaultColorHex = ThemeHelper.DEFAULT_THEIR_BUBBLE,
            previewView = previewTheirBubble,
            isBubble = true
        )
    }

    private fun setupItem(
        itemId: Int,
        title: String,
        prefColorKey: String,
        prefImageKey: String,
        defaultColorHex: String,
        previewView: View?,
        isBubble: Boolean = false,
        onColorChanged: ((Int) -> Unit)? = null
    ) {
        val container = findViewById<View>(itemId) ?: return

        container.findViewById<TextView>(R.id.tvSettingTitle).apply {
            text = title
        }

        val colorView = container.findViewById<View>(R.id.viewCurrentColor)
        val btnImage = container.findViewById<View>(R.id.btnPickImage)

        val defaultColor = defaultColorHex.toColorInt()
        val savedColor = ThemeHelper.getColor(this, prefColorKey, defaultColor)

        var hasImage = false
        if (prefImageKey != "NONE") {
            val savedUri = ThemeHelper.getImageUri(this, prefImageKey)
            if (savedUri != null) {
                hasImage = true
                val uri = savedUri.toUri()
                applyImageToView(colorView, uri)
                applyImageToView(previewView, uri)
            }
        }

        if (!hasImage) {
            colorView.setBackgroundColor(savedColor)
            if (isBubble && previewView != null) {
                previewView.backgroundTintList = android.content.res.ColorStateList.valueOf(savedColor)
                val drawableRes = if (previewView.id == R.id.tvPreviewMessageOutgoing) {
                    R.drawable.rounded_white
                } else {
                    R.drawable.rounded_search_bg
                }
                previewView.setBackgroundResource(drawableRes)
            } else {
                previewView?.setBackgroundColor(savedColor)
            }
        }

        container.findViewById<View>(R.id.btnPickColor).setOnClickListener {
            val dialog = ColorPickerDialog(savedColor) { newColor ->
                ThemeHelper.saveColor(this, prefColorKey, newColor)

                if (prefImageKey != "NONE") {
                    ThemeHelper.saveImageUri(this, prefImageKey, "")
                }

                colorView.setBackgroundColor(newColor)
                colorView.background = newColor.toDrawable()

                if (previewView != null) {
                    if (isBubble) {
                        val drawableRes = if (previewView.id == R.id.tvPreviewMessageOutgoing) {
                            R.drawable.rounded_white
                        } else {
                            R.drawable.rounded_search_bg
                        }
                        previewView.setBackgroundResource(drawableRes)
                        previewView.backgroundTintList = android.content.res.ColorStateList.valueOf(newColor)
                    } else {
                        previewView.setBackgroundColor(newColor)
                    }
                }
                onColorChanged?.invoke(newColor)
            }
            dialog.show(supportFragmentManager, "ColorPicker")
        }

        if (prefImageKey == "NONE") {
            btnImage.visibility = View.GONE
        } else {
            btnImage.setOnClickListener {
                currentImageTargetKey = prefImageKey
                currentImagePreviewView = previewView
                currentMiniPreviewView = colorView
                openGallery()
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryLauncher.launch(intent)
    }

    private fun applyImageToView(view: View?, uri: Uri) {
        if (view == null) return
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val drawable = bitmap.toDrawable(resources)
            view.background = drawable
            view.backgroundTintList = null
            inputStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
