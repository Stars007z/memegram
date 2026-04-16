package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.translation.TranslationSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LanguageViewModel(
    private val settings: Settings,
    private val userRepository: UserRepository,
    private val translationSettings: TranslationSettings
) : ViewModel() {

    companion object {
        const val KEY_LANG = "app_language"
    }

    private val _currentLang = MutableStateFlow(settings.getString(KEY_LANG, "en"))
    val currentLang: StateFlow<String> = _currentLang.asStateFlow()


    val autoTranslateEnabled: StateFlow<Boolean> = translationSettings.autoTranslateEnabled
    val targetLanguage: StateFlow<String> = translationSettings.targetLanguage
    val blacklistedLanguages: StateFlow<Set<String>> = translationSettings.blacklistedLanguages

    fun setAutoTranslateEnabled(enabled: Boolean) {
        translationSettings.setAutoTranslateEnabled(enabled)
        if (enabled) {
            translationSettings.syncBlacklistDefaults(_currentLang.value)
        }
    }

    fun setTargetLanguage(lang: String) {
        translationSettings.setTargetLanguage(lang)
    }

    fun toggleBlacklistLanguage(lang: String) {
        if (lang in translationSettings.blacklistedLanguages.value) {
            translationSettings.removeFromBlacklist(lang)
        } else {
            translationSettings.addToBlacklist(lang)
        }
    }

    // ── App language ────────────────────────────────────────────────

    fun setLanguage(lang: String) {
        val oldLang = _currentLang.value
        settings.putString(KEY_LANG, lang)
        _currentLang.value = lang
        if (oldLang != lang && oldLang != translationSettings.targetLanguage.value) {
            translationSettings.removeFromBlacklist(oldLang)
        }
        translationSettings.syncBlacklistDefaults(lang)
        viewModelScope.launch {
            userRepository.updateSettings(UpdateSettingsRequest(language = lang))
        }
    }

    init {
        translationSettings.syncBlacklistDefaults(_currentLang.value)

        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { s ->
                s.language.let { lang ->
                    val oldLang = _currentLang.value
                    settings.putString(KEY_LANG, lang)
                    _currentLang.value = lang
                    if (oldLang != lang && oldLang != translationSettings.targetLanguage.value) {
                        translationSettings.removeFromBlacklist(oldLang)
                    }
                    translationSettings.syncBlacklistDefaults(lang)
                }
            }
        }
    }
}
