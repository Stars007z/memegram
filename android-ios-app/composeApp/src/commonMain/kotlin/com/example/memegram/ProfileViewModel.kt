package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateProfileRequest
import com.example.memegram.data.repository.UserRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ProfileViewModel(
    private val userRepository: UserRepository,
    private val settings: Settings
) : ViewModel() {

    val username: StateFlow<String> = userRepository.profile
        .map { it?.username ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val bio: StateFlow<String> = userRepository.profile
        .map { it?.bio ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _avatarBytes = MutableStateFlow<ByteArray?>(
        settings.getStringOrNull("profile_avatar")?.let { runCatching { Base64.decode(it) }.getOrNull() }
    )
    val avatarBytes: StateFlow<ByteArray?> = _avatarBytes.asStateFlow()

    private val _coverBytes = MutableStateFlow<ByteArray?>(
        settings.getStringOrNull("profile_cover")?.let { runCatching { Base64.decode(it) }.getOrNull() }
    )
    val coverBytes: StateFlow<ByteArray?> = _coverBytes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { loadProfile() }

    fun loadProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            userRepository.loadProfile().onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun updateUsername(newUsername: String) {
        viewModelScope.launch {
            _isLoading.value = true
            userRepository.updateProfile(UpdateProfileRequest(username = newUsername))
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun updateBio(newBio: String) {
        viewModelScope.launch {
            _isLoading.value = true
            userRepository.updateProfile(UpdateProfileRequest(bio = newBio))
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun updateAvatar(bytes: ByteArray) {
        _avatarBytes.value = bytes
        settings.putString("profile_avatar", Base64.encode(bytes))
    }

    fun updateCover(bytes: ByteArray) {
        _coverBytes.value = bytes
        settings.putString("profile_cover", Base64.encode(bytes))
    }

    fun clearError() { _error.value = null }
}