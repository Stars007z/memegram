package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UserProfileResponse
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UserProfileViewModel(
    private val api: ApiService,
    private val contactsRepository: ContactsRepository
) : ViewModel() {

    private val _userProfile = MutableStateFlow<UserProfileResponse?>(null)
    val userProfile: StateFlow<UserProfileResponse?> = _userProfile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _avatarBytes = MutableStateFlow<ByteArray?>(null)
    val avatarBytes: StateFlow<ByteArray?> = _avatarBytes.asStateFlow()

    private val _coverBytes = MutableStateFlow<ByteArray?>(null)
    val coverBytes: StateFlow<ByteArray?> = _coverBytes.asStateFlow()

    fun loadUser(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val profile = api.getUserById(userId)
                _userProfile.value = profile
                profile.avatarMediaId?.let { fetchImage(it, "avatar") }
                profile.profileBackgroundMediaId?.let { fetchImage(it, "cover") }
            } catch (e: Exception) {
                _actionMessage.value = "Error loading profile"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchImage(mediaId: String, type: String) {
        try {
            val downloadInfo = api.getItemDownloadUrl(mediaId)
            val bytes = api.downloadBytesFromUrl(downloadInfo.downloadUrl)
            when (type) {
                "avatar" -> _avatarBytes.value = bytes
                "cover" -> _coverBytes.value = bytes
            }
        } catch (e: Exception) {
            println("UserProfileVM: Failed to fetch $type image: ${e.message}")
        }
    }

    fun addToContacts() {
        val pubKey = _userProfile.value?.userPublicKey ?: return
        viewModelScope.launch {
            try {
                contactsRepository.addContact(pubKey)
                _actionMessage.value = "User added to contacts"
            } catch (e: Exception) {
                _actionMessage.value = "Error: User already in contacts or unavailable"
            }
        }
    }

    fun clearMessage() {
        _actionMessage.value = null
    }
}
