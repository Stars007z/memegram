package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppearanceViewModel(
    private val themePreferences: ThemePreferences,
    private val userRepository: UserRepository
) : ViewModel() {

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

    init {
        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { settings ->
                settings.topBarColor?.let { hex ->
                    runCatching {
                        val colorInt = hex.removePrefix("#").toLong(16).toInt()
                        themePreferences.saveColor("topbar", Color(colorInt or 0xFF000000.toInt()))
                    }
                }
            }
        }
    }

    fun updateColor(key: String, color: Color) {
        themePreferences.saveColor(key, color)
        when (key) {
            "chatbg"      -> _chatBgColor.value = color
            "mybubble"    -> _myBubbleColor.value = color
            "theirbubble" -> _theirBubbleColor.value = color
            "topbar"      -> syncTopBarToServer(color)
        }
    }

    private fun syncTopBarToServer(color: Color) {
        viewModelScope.launch {
            val hex = "#%06X".format(color.toArgb() and 0xFFFFFF)
            userRepository.updateSettings(UpdateSettingsRequest(topBarColor = hex))
        }
    }
}