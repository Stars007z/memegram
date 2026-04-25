package com.example.memegram

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ThemeViewModel(
    private val themePreferences: ThemePreferences,
    private val appearance: AppearanceRepository,
) : ViewModel() {

    val topBarColor: StateFlow<androidx.compose.ui.graphics.Color> = appearance.topBarColor
    val topBarImage: StateFlow<ByteArray?> = appearance.topBarImage

    val topBarTextColor: StateFlow<androidx.compose.ui.graphics.Color?> = appearance.topBarTextColor

    private val _isDarkMode = MutableStateFlow(themePreferences.isDarkMode())
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun refreshTheme() {
        appearance.refresh()
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
