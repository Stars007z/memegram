package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppearanceViewModel(private val themePreferences: ThemePreferences) : ViewModel() {
    private val _chatBgColor = MutableStateFlow(themePreferences.getColor("chatbg", ThemePreferences.DefaultChatBg))
    val chatBgColor = _chatBgColor.asStateFlow()

    private val _myBubbleColor = MutableStateFlow(themePreferences.getColor("mybubble", ThemePreferences.DefaultMyBubble))
    val myBubbleColor = _myBubbleColor.asStateFlow()

    private val _theirBubbleColor = MutableStateFlow(themePreferences.getColor("theirbubble", ThemePreferences.DefaultTheirBubble))
    val theirBubbleColor = _theirBubbleColor.asStateFlow()

    fun updateColor(key: String, color: Color) {
        themePreferences.saveColor(key, color)
        when (key) {
            "chatbg" -> _chatBgColor.value = color
            "mybubble" -> _myBubbleColor.value = color
            "theirbubble" -> _theirBubbleColor.value = color
        }
    }
}