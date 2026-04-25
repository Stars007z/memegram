package com.example.memegram

import androidx.compose.ui.graphics.Color
import com.russhwolf.settings.Settings

class ThemePreferences(private val settings: Settings) {

    companion object {
        val DefaultTopBar = Color(0xFF6075F2)
        val DefaultChatBg = Color(0xFFF5F5F5)
        val DefaultMyBubble = Color(0xFFD1C4E9)
        val DefaultTheirBubble = Color(0xFFFFFFFF)

        private const val KEY_DARK_MODE = "app_dark_mode"
        private const val KEY_TRANSPARENT_BUBBLES_ENABLED = "app_transparent_bubbles_enabled"
        private const val KEY_BUBBLE_TRANSPARENCY = "app_bubble_transparency"

        const val DEFAULT_BUBBLE_TRANSPARENCY = 0.5f

        const val TEXT_COLOR_KEY_PREFIX = "app_text_color_"
    }

    fun saveColor(key: String, color: Color) {
        settings.putLong(key, color.value.toLong())
    }

    fun getColor(key: String, defaultColor: Color): Color {
        val savedLong = settings.getLongOrNull(key) ?: return defaultColor
        return Color(savedLong.toULong())
    }

    fun isDarkMode(): Boolean {
        return settings.getBoolean(KEY_DARK_MODE, false)
    }

    fun setDarkMode(enabled: Boolean) {
        settings.putBoolean(KEY_DARK_MODE, enabled)
    }

    fun isTransparentBubbles(): Boolean {
        return settings.getBoolean(KEY_TRANSPARENT_BUBBLES_ENABLED, false)
    }

    fun setTransparentBubbles(enabled: Boolean) {
        settings.putBoolean(KEY_TRANSPARENT_BUBBLES_ENABLED, enabled)
    }

    fun getBubbleTransparency(): Float {
        val raw = settings.getDoubleOrNull(KEY_BUBBLE_TRANSPARENCY)
            ?: return DEFAULT_BUBBLE_TRANSPARENCY
        return raw.toFloat().coerceIn(0f, 1f)
    }

    fun setBubbleTransparency(value: Float) {
        settings.putDouble(KEY_BUBBLE_TRANSPARENCY, value.coerceIn(0f, 1f).toDouble())
    }

    fun getTextColor(surfaceKey: String): Color? {
        val storageKey = TEXT_COLOR_KEY_PREFIX + surfaceKey
        val savedLong = settings.getLongOrNull(storageKey) ?: return null
        return Color(savedLong.toULong())
    }

    fun setTextColor(surfaceKey: String, color: Color) {
        settings.putLong(TEXT_COLOR_KEY_PREFIX + surfaceKey, color.value.toLong())
    }

    fun clearTextColor(surfaceKey: String) {
        settings.remove(TEXT_COLOR_KEY_PREFIX + surfaceKey)
    }
}
