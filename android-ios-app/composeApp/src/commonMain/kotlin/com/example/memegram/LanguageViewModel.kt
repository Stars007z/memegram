package com.example.memegram

import androidx.lifecycle.ViewModel
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LanguageViewModel(private val settings: Settings) : ViewModel() {

    companion object {
        const val KEY_LANG = "app_language"
    }

    private val _currentLang = MutableStateFlow(settings.getString(KEY_LANG, "en"))
    val currentLang: StateFlow<String> = _currentLang.asStateFlow()

    fun setLanguage(langCode: String) {
        if (_currentLang.value == langCode) return
        settings.putString(KEY_LANG, langCode)
        _currentLang.value = langCode
    }
}