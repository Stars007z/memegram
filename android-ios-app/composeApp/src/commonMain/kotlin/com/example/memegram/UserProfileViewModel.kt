package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.BlockUserRequest
import com.example.memegram.data.models.UserProfileResponse
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UserProfileViewModel(
    private val api: ApiService,
    private val contactsRepository: ContactsRepository,
    private val blockedUsersCache: BlockedUsersCache
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

    private val _isContact = MutableStateFlow(false)
    val isContact: StateFlow<Boolean> = _isContact.asStateFlow()

    val isBlocked: StateFlow<Boolean> = combine(
        _userProfile, blockedUsersCache.blockedIds
    ) { profile, ids -> profile?.id?.let { it in ids } ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isBlockedByPeer: StateFlow<Boolean> = _userProfile
        .map { it?.isBlockedByPeer == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadUser(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val profile = api.getUserById(userId)
                _userProfile.value = profile
                profile.avatarMediaId?.let { fetchImage(it, "avatar") }
                profile.profileBackgroundMediaId?.let { fetchImage(it, "cover") }
                try {
                    val contacts = contactsRepository.getContacts(limit = 200, offset = 0).getOrNull()
                    _isContact.value = contacts?.any { it.contactUserId == userId } == true
                } catch (_: Exception) {}
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
                _isContact.value = true
                _actionMessage.value = "User added to contacts"
            } catch (e: Exception) {
                _actionMessage.value = "Error: User already in contacts or unavailable"
            }
        }
    }

    fun blockUser() {
        val userId = _userProfile.value?.id ?: return
        viewModelScope.launch {
            try {
                api.blockUser(BlockUserRequest(userId))
                blockedUsersCache.add(userId)
            } catch (e: Exception) {
                _actionMessage.value = e.message
            }
        }
    }

    fun unblockUser() {
        val userId = _userProfile.value?.id ?: return
        viewModelScope.launch {
            try {
                api.unblockUser(userId)
                blockedUsersCache.remove(userId)
            } catch (e: Exception) {
                _actionMessage.value = e.message
            }
        }
    }

    fun clearMessage() {
        _actionMessage.value = null
    }
}
