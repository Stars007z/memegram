package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.localization.AppStrings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PrivacyViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _profileVisibleTo = MutableStateFlow("everybody")
    val profileVisibleTo: StateFlow<String> = _profileVisibleTo.asStateFlow()

    private val _lastActiveVisibleTo = MutableStateFlow("everybody")
    val lastActiveVisibleTo: StateFlow<String> = _lastActiveVisibleTo.asStateFlow()

    private val _autoDeleteDays = MutableStateFlow<Int?>(null)
    val autoDeleteDays: StateFlow<Int?> = _autoDeleteDays.asStateFlow()

    private val _accountDeleted = MutableStateFlow(false)
    val accountDeleted: StateFlow<Boolean> = _accountDeleted.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { s ->
                _profileVisibleTo.value = s.profileVisibleTo
                _lastActiveVisibleTo.value = s.lastActiveVisibleTo
                _autoDeleteDays.value = s.accountAutoDeleteAfterDays
            }
        }
    }

    fun setProfileVisibleTo(value: String) {
        _profileVisibleTo.value = value
        viewModelScope.launch {
            userRepository.updateSettings(UpdateSettingsRequest(profileVisibleTo = value))
                .onFailure { _error.value = it.message }
        }
    }

    fun setLastActiveVisibleTo(value: String) {
        _lastActiveVisibleTo.value = value
        viewModelScope.launch {
            userRepository.updateSettings(UpdateSettingsRequest(lastActiveVisibleTo = value))
                .onFailure { _error.value = it.message }
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
                    sessionManager.clear()
                    sessionManager.clearDeviceId()
                    _accountDeleted.value = true
                }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun clearError() { _error.value = null }

    companion object {
        fun visibilityLabel(value: String, s: AppStrings) = when (value) {
            "contacts" -> s.visContacts
            "nobody"   -> s.visNobody
            else       -> s.visEverybody
        }
        fun visibilityValue(label: String, s: AppStrings) = when (label) {
            s.visContacts -> "contacts"
            s.visNobody   -> "nobody"
            else          -> "everybody"
        }
        fun visibilityOptions(s: AppStrings) = listOf(s.visEverybody, s.visContacts, s.visNobody)

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
