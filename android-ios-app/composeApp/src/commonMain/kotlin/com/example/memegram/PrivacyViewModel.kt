package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.network.ApiException
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.localization.AppStrings
import com.example.memegram.nsfw.NsfwService
import com.example.memegram.nsfw.NsfwSettings
import com.example.memegram.push.PushTokenProvider
import com.example.memegram.translation.ModelDownloadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PrivacyViewModel(
    private val userRepository: UserRepository,
    private val pushTokenProvider: PushTokenProvider,
    private val nsfwSettings: NsfwSettings,
    private val nsfwService: NsfwService,
) : ViewModel() {

    private val _autoDeleteDays = MutableStateFlow<Int?>(null)
    val autoDeleteDays: StateFlow<Int?> = _autoDeleteDays.asStateFlow()

    private val _accountDeleted = MutableStateFlow(false)
    val accountDeleted: StateFlow<Boolean> = _accountDeleted.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val nsfwFilterEnabled: StateFlow<Boolean> = nsfwSettings.filterEnabled
    val nsfwSupported: Boolean = nsfwService.isSupported()

    private val _nsfwModelState = MutableStateFlow<ModelDownloadState>(
        if (nsfwService.isModelAvailable()) ModelDownloadState.Ready else ModelDownloadState.Idle
    )
    val nsfwModelState: StateFlow<ModelDownloadState> = _nsfwModelState.asStateFlow()

    private val _nsfwModelSize = MutableStateFlow(nsfwService.getModelSize())
    val nsfwModelSize: StateFlow<Long> = _nsfwModelSize.asStateFlow()

    private var nsfwDownloadJob: Job? = null

    init {
        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { s ->
                _autoDeleteDays.value = s.accountAutoDeleteAfterDays
            }
        }
    }

    fun setAutoDeleteDays(days: Int?) {
        _autoDeleteDays.value = days
        viewModelScope.launch {
            userRepository.updateSettings(UpdateSettingsRequest(accountAutoDeleteAfterDays = days))
                .onFailure { _error.value = it.message }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _isLoading.value = true
            userRepository.deleteAccount()
                .onSuccess {
                    runCatching { pushTokenProvider.deleteToken() }
                        .onFailure { println("MemegramDebug [AccountDelete] push.delete.fail: ${it.message}") }
                    _accountDeleted.value = true
                }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun setNsfwFilterEnabled(enabled: Boolean) {
        if (!nsfwService.isSupported()) return
        if (enabled && !nsfwService.isModelAvailable()) return
        nsfwSettings.setFilterEnabled(enabled)
    }

    fun downloadNsfwModel() {
        if (!nsfwService.isSupported()) return
        if (nsfwDownloadJob?.isActive == true) return
        if (nsfwService.isModelAvailable()) {
            _nsfwModelSize.value = nsfwService.getModelSize()
            _nsfwModelState.value = ModelDownloadState.Ready
            return
        }
        _nsfwModelState.value = ModelDownloadState.Downloading(0, -1)
        nsfwDownloadJob = viewModelScope.launch {
            nsfwService.downloadModel()
                .catch { e ->
                    _nsfwModelState.value = ModelDownloadState.Failed(formatDownloadError(e))
                }
                .onEach { progress: ModelDownloadProgress ->
                    _nsfwModelState.value = ModelDownloadState.Downloading(
                        progress.bytesDownloaded,
                        progress.totalBytes,
                    )
                }
                .collect {}

            if (nsfwService.isModelAvailable()) {
                _nsfwModelSize.value = nsfwService.getModelSize()
                _nsfwModelState.value = ModelDownloadState.Ready
                nsfwSettings.notifyModelStateChanged()
            } else if (_nsfwModelState.value is ModelDownloadState.Downloading) {
                _nsfwModelState.value = ModelDownloadState.Failed("Download incomplete")
            }
        }
    }

    fun cancelNsfwDownload() {
        nsfwDownloadJob?.cancel()
        nsfwDownloadJob = null
        _nsfwModelState.value =
            if (nsfwService.isModelAvailable()) ModelDownloadState.Ready else ModelDownloadState.Idle
        _nsfwModelSize.value = nsfwService.getModelSize()
    }

    fun deleteNsfwModel() {
        if (!nsfwService.isSupported()) return
        viewModelScope.launch {
            nsfwService.deleteModel()
            nsfwSettings.setFilterEnabled(false)
            _nsfwModelSize.value = 0L
            _nsfwModelState.value = ModelDownloadState.Idle
            nsfwSettings.notifyModelStateChanged()
        }
    }

    fun clearError() { _error.value = null }

    private fun formatDownloadError(e: Throwable): String {
        if (e is ApiException) {
            return "HTTP ${e.status.value}: ${e.status.description}"
        }
        return e.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(240)
            ?.takeIf { it.isNotBlank() }
            ?: "Download failed"
    }

    companion object {
        fun daysLabel(days: Int?, s: AppStrings) = when (days) {
            30  -> s.days1Month
            90  -> s.days3Months
            180 -> s.days6Months
            365 -> s.days1Year
            else -> s.daysOff
        }
        fun daysValue(label: String, s: AppStrings) = when (label) {
            s.days1Month  -> 30
            s.days3Months -> 90
            s.days6Months -> 180
            s.days1Year   -> 365
            else          -> null
        }
        fun autoDeleteOptions(s: AppStrings) =
            listOf(s.daysOff, s.days1Month, s.days3Months, s.days6Months, s.days1Year)
    }
}
