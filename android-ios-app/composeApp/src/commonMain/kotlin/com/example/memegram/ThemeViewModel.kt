package com.example.memegram

import androidx.lifecycle.ViewModel
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ThemeViewModel(
    private val themePreferences: ThemePreferences,
    private val settings: Settings
) : ViewModel() {
    private val _topBarColor = MutableStateFlow(themePreferences.getColor("topbar", ThemePreferences.DefaultTopBar))
    val topBarColor = _topBarColor.asStateFlow()

    private val _topBarImage = MutableStateFlow<ByteArray?>(
        settings.getStringOrNull("appearance_topbar_image")?.let {
            runCatching { Base64.decode(it) }.getOrNull()
        }
    )
    val topBarImage = _topBarImage.asStateFlow()

    private val _isDarkMode = MutableStateFlow(themePreferences.isDarkMode())
    val isDarkMode = _isDarkMode.asStateFlow()

    fun refreshTheme() {
        _topBarColor.value = themePreferences.getColor("topbar", ThemePreferences.DefaultTopBar)
        _topBarImage.value = settings.getStringOrNull("appearance_topbar_image")?.let {
            runCatching { Base64.decode(it) }.getOrNull()
        }
    }

    fun toggleDarkMode() {
        val newValue = !_isDarkMode.value
        themePreferences.setDarkMode(newValue)
        _isDarkMode.value = newValue
    }

    fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
        _isDarkMode.value = enabled
    }
}