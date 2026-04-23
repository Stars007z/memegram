package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.InitiateItemUploadRequest
import com.example.memegram.data.models.UpdateProfileRequest
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.data.repository.UserRepository
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
    private val api: ApiService
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

    private fun isDecodableImage(bytes: ByteArray): Boolean =
        runCatching { bytes.decodeToImageBitmap() }.isSuccess

    private fun loadValidatedImage(key: String): ByteArray? {
        val raw = settings.getStringOrNull(key) ?: return null
        val bytes = runCatching { Base64.decode(raw) }.getOrNull()
        if (bytes == null || bytes.isEmpty() || !isDecodableImage(bytes)) {
            println("MemegramDebug [ProfileVM] $key invalid, clearing cached blob")
            settings.remove(key)
            settings.remove(key + "_media_id")
            return null
        }
        return bytes
    }

    private val _avatarBytes = MutableStateFlow<ByteArray?>(loadValidatedImage("profile_avatar"))
    val avatarBytes: StateFlow<ByteArray?> = _avatarBytes.asStateFlow()

    private val _coverBytes = MutableStateFlow<ByteArray?>(loadValidatedImage("profile_cover"))
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

            _avatarBytes.value = loadValidatedImage("profile_avatar")
            _coverBytes.value = loadValidatedImage("profile_cover")

            userRepository.loadProfile()
                .onSuccess { profile ->
                    profile.avatarMediaId?.let { mediaId ->
                        if (settings.getStringOrNull("profile_avatar_media_id") != mediaId) {
                            fetchAndCacheImage(mediaId, "avatar")
                        }
                    }
                    profile.profileBackgroundMediaId?.let { mediaId ->
                        if (settings.getStringOrNull("profile_cover_media_id") != mediaId) {
                            fetchAndCacheImage(mediaId, "cover")
                        }
                    }
                }
                .onFailure { _error.value = "Error loading profile: ${it.message}" }
            _isLoading.value = false
        }
    }

    private suspend fun fetchAndCacheImage(mediaId: String, type: String) {
        try {
            val downloadInfo = api.getItemDownloadUrl(mediaId)
            val bytes = api.downloadBytesFromUrl(downloadInfo.downloadUrl)
            if (!isDecodableImage(bytes)) {
                println("MemegramDebug [ProfileVM] fetched $type bytes are not decodable (size=${bytes.size}), skipping cache")
                return
            }
            when (type) {
                "avatar" -> {
                    _avatarBytes.value = bytes
                    settings.putString("profile_avatar", Base64.encode(bytes))
                    settings.putString("profile_avatar_media_id", mediaId)
                }
                "cover" -> {
                    _coverBytes.value = bytes
                    settings.putString("profile_cover", Base64.encode(bytes))
                    settings.putString("profile_cover_media_id", mediaId)
                }
            }
        } catch (e: Exception) {
            println("ProfileVM: Failed to fetch $type image: ${e.message}")
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
                .onSuccess { _message.value = "Profile updated!" }
                .onFailure { _error.value = "Error saving: ${it.message}" }

            _isLoading.value = false
        }
    }

    fun updateAvatar(bytes: ByteArray) {
        viewModelScope.launch {
            if (!isDecodableImage(bytes)) {
                _error.value = "Selected avatar is not a valid image"
                return@launch
            }
            _isLoading.value = true
            try {
                val mediaId = uploadImageToItemStorage(bytes, "avatar", "image/jpeg")
                userRepository.updateProfile(UpdateProfileRequest(avatarMediaId = mediaId))
                _avatarBytes.value = bytes
                settings.putString("profile_avatar", Base64.encode(bytes))
                settings.putString("profile_avatar_media_id", mediaId)
                _message.value = "Avatar updated!"
            } catch (e: Exception) {
                _error.value = "Error uploading avatar: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateCover(bytes: ByteArray) {
        viewModelScope.launch {
            if (!isDecodableImage(bytes)) {
                _error.value = "Selected cover is not a valid image"
                return@launch
            }
            _isLoading.value = true
            try {
                val mediaId = uploadImageToItemStorage(bytes, "profile_background", "image/jpeg")
                userRepository.updateProfile(UpdateProfileRequest(profileBackgroundMediaId = mediaId))
                _coverBytes.value = bytes
                settings.putString("profile_cover", Base64.encode(bytes))
                settings.putString("profile_cover_media_id", mediaId)
                _message.value = "Cover updated!"
            } catch (e: Exception) {
                _error.value = "Error uploading cover: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun uploadImageToItemStorage(
        bytes: ByteArray,
        itemType: String,
        mimeType: String
    ): String {
        val initiateResp = api.initiateItemUpload(
            InitiateItemUploadRequest(
                itemType = itemType,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong()
            )
        )
        api.uploadBytesToPresignedUrl(initiateResp.uploadUrl, bytes, mimeType)
        val confirmResp = api.confirmItemUpload(initiateResp.itemId)
        if (!confirmResp.success) {
            throw Exception("Upload confirmation failed for item ${initiateResp.itemId}")
        }
        return initiateResp.itemId
    }

    fun clearCache() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                chatRepository.clearAllLocalData()
                _message.value = "Local message cache cleared"
            } catch (e: Exception) {
                _error.value = "Error clearing cache: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearMessage() { _message.value = null }
}
