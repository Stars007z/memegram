package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.LogoutRequest
import com.example.memegram.data.models.UpdateProfileRequest
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.mls.MlsManager
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ProfileViewModel(
    private val userRepository: UserRepository,
    private val settings: Settings,
    private val chatRepository: ChatRepository,
    private val sessionManager: SessionManager,
    private val api: ApiService,
    private val mlsManager: MlsManager
) : ViewModel() {

    val myPublicKey: StateFlow<String> = userRepository.profile
        .map { it?.userPublicKey ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val username: StateFlow<String> = userRepository.profile
        .map { it?.username ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val bio: StateFlow<String> = userRepository.profile
        .map { it?.bio ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val userId: StateFlow<String> = userRepository.profile
        .map { it?.id ?: "" }
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            userRepository.loadProfile().onFailure { _error.value = "Ошибка загрузки: ${it.message}" }
            _isLoading.value = false
        }
    }

    fun updateProfile(newUsername: String, newBio: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val request = UpdateProfileRequest(
                username = newUsername.takeIf { it.isNotBlank() },
                bio = newBio.takeIf { it.isNotBlank() }
            )

            userRepository.updateProfile(request)
                .onSuccess { _message.value = "Профиль успешно обновлен!" }
                .onFailure { _error.value = "Ошибка сохранения: ${it.message}" }

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

    fun clearCache() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                chatRepository.clearAllLocalData()
                _message.value = "Локальный кэш сообщений очищен"
            } catch (e: Exception) {
                _error.value = "Ошибка при очистке кэша: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                sessionManager.getAccessToken()?.let { token ->
                    api.logout(LogoutRequest(token))
                }
            } catch (_: Exception) {
            } finally {
                chatRepository.clearAllLocalData()
                mlsManager.clearAll()
                settings.remove("profile_avatar")
                settings.remove("profile_cover")
                sessionManager.clear()

                _isLoading.value = false
                onDone()
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearMessage() { _message.value = null }
}