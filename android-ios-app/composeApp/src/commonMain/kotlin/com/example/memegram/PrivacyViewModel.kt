package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
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
        fun visibilityLabel(value: String) = when (value) {
            "contacts" -> "Контакты"
            "nobody"   -> "Никто"
            else       -> "Все"
        }
        fun visibilityValue(label: String) = when (label) {
            "Контакты" -> "contacts"
            "Никто"    -> "nobody"
            else       -> "everybody"
        }
        val visibilityOptions = listOf("Все", "Контакты", "Никто")

        fun daysLabel(days: Int?) = when (days) {
            30  -> "1 месяц"
            90  -> "3 месяца"
            180 -> "6 месяцев"
            365 -> "1 год"
            else -> "Выкл"
        }
        fun daysValue(label: String) = when (label) {
            "1 месяц"  -> 30
            "3 месяца" -> 90
            "6 месяцев"-> 180
            "1 год"    -> 365
            else       -> null
        }
        val autoDeleteOptions = listOf("Выкл", "1 месяц", "3 месяца", "6 месяцев", "1 год")
    }
}