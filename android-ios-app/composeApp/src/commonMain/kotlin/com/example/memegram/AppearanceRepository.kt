package com.example.memegram

import androidx.compose.ui.graphics.Color
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class AppearanceRepository(
    private val themePreferences: ThemePreferences,
    private val settings: Settings,
) {

    private val _chatBgColor = MutableStateFlow(
        themePreferences.getColor("chatbg", ThemePreferences.DefaultChatBg)
    )
    val chatBgColor: StateFlow<Color> = _chatBgColor.asStateFlow()

    private val _myBubbleColor = MutableStateFlow(
        themePreferences.getColor("mybubble", ThemePreferences.DefaultMyBubble)
    )
    val myBubbleColor: StateFlow<Color> = _myBubbleColor.asStateFlow()

    private val _theirBubbleColor = MutableStateFlow(
        themePreferences.getColor("theirbubble", ThemePreferences.DefaultTheirBubble)
    )
    val theirBubbleColor: StateFlow<Color> = _theirBubbleColor.asStateFlow()

    private val _topBarColor = MutableStateFlow(
        themePreferences.getColor("topbar", ThemePreferences.DefaultTopBar)
    )
    val topBarColor: StateFlow<Color> = _topBarColor.asStateFlow()

    private val _chatBgImage = MutableStateFlow(loadImage("chatbg"))
    val chatBgImage: StateFlow<ByteArray?> = _chatBgImage.asStateFlow()

    private val _myBubbleImage = MutableStateFlow(loadImage("mybubble"))
    val myBubbleImage: StateFlow<ByteArray?> = _myBubbleImage.asStateFlow()

    private val _theirBubbleImage = MutableStateFlow(loadImage("theirbubble"))
    val theirBubbleImage: StateFlow<ByteArray?> = _theirBubbleImage.asStateFlow()

    private val _topBarImage = MutableStateFlow(loadImage("topbar"))
    val topBarImage: StateFlow<ByteArray?> = _topBarImage.asStateFlow()

    // ── Transparency / custom text colors ────────────────────────────────

    private val _transparentBubbles = MutableStateFlow(themePreferences.isTransparentBubbles())
    val transparentBubbles: StateFlow<Boolean> = _transparentBubbles.asStateFlow()

    private val _bubbleTransparency = MutableStateFlow(themePreferences.getBubbleTransparency())
    val bubbleTransparency: StateFlow<Float> = _bubbleTransparency.asStateFlow()

    private val _myBubbleTextColor = MutableStateFlow(themePreferences.getTextColor("mybubble"))
    val myBubbleTextColor: StateFlow<Color?> = _myBubbleTextColor.asStateFlow()

    private val _theirBubbleTextColor = MutableStateFlow(themePreferences.getTextColor("theirbubble"))
    val theirBubbleTextColor: StateFlow<Color?> = _theirBubbleTextColor.asStateFlow()

    private val _topBarTextColor = MutableStateFlow(themePreferences.getTextColor("topbar"))
    val topBarTextColor: StateFlow<Color?> = _topBarTextColor.asStateFlow()

    private val _chatBgTextColor = MutableStateFlow(themePreferences.getTextColor("chatbg"))
    val chatBgTextColor: StateFlow<Color?> = _chatBgTextColor.asStateFlow()

    private val _imageGeneration = MutableStateFlow(0L)
    val imageGeneration: StateFlow<Long> = _imageGeneration.asStateFlow()

    fun setColor(key: String, color: Color) {
        themePreferences.saveColor(key, color)
        when (key) {
            "chatbg" -> _chatBgColor.value = color
            "mybubble" -> _myBubbleColor.value = color
            "theirbubble" -> _theirBubbleColor.value = color
            "topbar" -> _topBarColor.value = color
        }
        _imageGeneration.value = _imageGeneration.value + 1
    }

    fun setImage(key: String, bytes: ByteArray) {
        settings.putString("appearance_${key}_image", Base64.encode(bytes))
        when (key) {
            "chatbg" -> _chatBgImage.value = bytes
            "mybubble" -> _myBubbleImage.value = bytes
            "theirbubble" -> _theirBubbleImage.value = bytes
            "topbar" -> _topBarImage.value = bytes
        }
        _imageGeneration.value = _imageGeneration.value + 1
    }

    fun clearImage(key: String) {
        val localKey = "appearance_${key}_image"
        settings.remove(localKey)
        settings.remove("${localKey}_media_id")
        when (key) {
            "chatbg" -> _chatBgImage.value = null
            "mybubble" -> _myBubbleImage.value = null
            "theirbubble" -> _theirBubbleImage.value = null
            "topbar" -> _topBarImage.value = null
        }
        _imageGeneration.value = _imageGeneration.value + 1
    }

    fun setTransparentBubbles(enabled: Boolean) {
        themePreferences.setTransparentBubbles(enabled)
        _transparentBubbles.value = enabled
    }

    fun setBubbleTransparency(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        themePreferences.setBubbleTransparency(clamped)
        _bubbleTransparency.value = clamped
    }

    fun setTextColor(surfaceKey: String, color: Color) {
        themePreferences.setTextColor(surfaceKey, color)
        when (surfaceKey) {
            "mybubble" -> _myBubbleTextColor.value = color
            "theirbubble" -> _theirBubbleTextColor.value = color
            "topbar" -> _topBarTextColor.value = color
            "chatbg" -> _chatBgTextColor.value = color
        }
    }

    fun clearTextColor(surfaceKey: String) {
        themePreferences.clearTextColor(surfaceKey)
        when (surfaceKey) {
            "mybubble" -> _myBubbleTextColor.value = null
            "theirbubble" -> _theirBubbleTextColor.value = null
            "topbar" -> _topBarTextColor.value = null
            "chatbg" -> _chatBgTextColor.value = null
        }
    }

    fun refresh() {
        _chatBgColor.value = themePreferences.getColor("chatbg", ThemePreferences.DefaultChatBg)
        _myBubbleColor.value = themePreferences.getColor("mybubble", ThemePreferences.DefaultMyBubble)
        _theirBubbleColor.value = themePreferences.getColor("theirbubble", ThemePreferences.DefaultTheirBubble)
        _topBarColor.value = themePreferences.getColor("topbar", ThemePreferences.DefaultTopBar)

        _chatBgImage.value = loadImage("chatbg")
        _myBubbleImage.value = loadImage("mybubble")
        _theirBubbleImage.value = loadImage("theirbubble")
        _topBarImage.value = loadImage("topbar")

        _transparentBubbles.value = themePreferences.isTransparentBubbles()
        _bubbleTransparency.value = themePreferences.getBubbleTransparency()
        _myBubbleTextColor.value = themePreferences.getTextColor("mybubble")
        _theirBubbleTextColor.value = themePreferences.getTextColor("theirbubble")
        _topBarTextColor.value = themePreferences.getTextColor("topbar")
        _chatBgTextColor.value = themePreferences.getTextColor("chatbg")
        _imageGeneration.value = _imageGeneration.value + 1
    }

    private fun loadImage(key: String): ByteArray? =
        settings.getStringOrNull("appearance_${key}_image")
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
}
