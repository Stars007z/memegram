package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LanguageViewModel(
    private val settings: Settings,
    private val userRepository: UserRepository
) : ViewModel() {

    companion object {
        const val KEY_LANG = "app_language"
    }

    private val _currentLang = MutableStateFlow(settings.getString(KEY_LANG, "en"))
    val currentLang: StateFlow<String> = _currentLang.asStateFlow()

    fun setLanguage(lang: String) {
        settings.putString(KEY_LANG, lang)
        _currentLang.value = lang
        viewModelScope.launch {
            userRepository.updateSettings(UpdateSettingsRequest(language = lang))
        }
    }

    init {
        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { s ->
                s.language.let { lang ->
                    settings.putString(KEY_LANG, lang)
                    _currentLang.value = lang
                }
            }
        }
    }
}