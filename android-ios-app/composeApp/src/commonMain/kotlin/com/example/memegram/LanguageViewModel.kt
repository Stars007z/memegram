package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.translation.ModelDownloadProgress
import com.example.memegram.translation.TranslationService
import com.example.memegram.translation.TranslationSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : ModelDownloadState {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toDouble() / totalBytes).toFloat() else 0f
    }
    data object Ready : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

class LanguageViewModel(
    private val settings: Settings,
    private val userRepository: UserRepository,
    private val translationSettings: TranslationSettings,
    private val translationService: TranslationService,
) : ViewModel() {

    companion object {
        const val KEY_LANG = "app_language"
    }

    private val _currentLang = MutableStateFlow(settings.getString(KEY_LANG, "en"))
    val currentLang: StateFlow<String> = _currentLang.asStateFlow()

    val autoTranslateEnabled: StateFlow<Boolean> = translationSettings.autoTranslateEnabled
    val targetLanguage: StateFlow<String> = translationSettings.targetLanguage
    val blacklistedLanguages: StateFlow<Set<String>> = translationSettings.blacklistedLanguages

    private val _modelState = MutableStateFlow<ModelDownloadState>(
        if (translationService.isModelAvailable()) ModelDownloadState.Ready
        else ModelDownloadState.Idle
    )
    val modelState: StateFlow<ModelDownloadState> = _modelState.asStateFlow()

    private val _modelSize = MutableStateFlow(translationService.getModelSize())
    val modelSize: StateFlow<Long> = _modelSize.asStateFlow()

    private var downloadJob: Job? = null

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        if (translationService.isModelAvailable()) {
            _modelState.value = ModelDownloadState.Ready
            _modelSize.value = translationService.getModelSize()
            return
        }
        _modelState.value = ModelDownloadState.Downloading(0, -1)
        downloadJob = viewModelScope.launch {
            translationService.downloadModel()
                .catch { e ->
                    _modelState.value = ModelDownloadState.Failed(e.message ?: "Download failed")
                }
                .onEach { p: ModelDownloadProgress ->
                    _modelState.value = ModelDownloadState.Downloading(p.bytesDownloaded, p.totalBytes)
                }
                .collect {}
            if (translationService.isModelAvailable()) {
                _modelSize.value = translationService.getModelSize()
                _modelState.value = ModelDownloadState.Ready
            } else if (_modelState.value is ModelDownloadState.Downloading) {
                _modelState.value = ModelDownloadState.Failed("Download incomplete")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _modelState.value =
            if (translationService.isModelAvailable()) ModelDownloadState.Ready
            else ModelDownloadState.Idle
        _modelSize.value = translationService.getModelSize()
    }

    fun deleteModel() {
        viewModelScope.launch {
            translationService.deleteModel()
            _modelSize.value = 0
            _modelState.value = ModelDownloadState.Idle
        }
    }

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
