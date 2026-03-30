package com.example.memegram

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeViewModel(
    private val themePreferences: ThemePreferences
) : ViewModel() {
    private val _topBarColor = MutableStateFlow(themePreferences.getColor("topbar", ThemePreferences.DefaultTopBar))
    val topBarColor = _topBarColor.asStateFlow()

    fun refreshTheme() {
        _topBarColor.value = themePreferences.getColor("topbar", ThemePreferences.DefaultTopBar)
    }
}