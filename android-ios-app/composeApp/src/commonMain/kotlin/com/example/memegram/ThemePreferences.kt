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
}