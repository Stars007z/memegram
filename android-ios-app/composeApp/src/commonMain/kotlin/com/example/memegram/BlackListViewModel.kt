package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.BlockedEntry
import com.example.memegram.data.repository.ContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BlackListViewModel(
    private val contactsRepository: ContactsRepository
) : ViewModel() {

    private val _blockedUsers = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val blockedUsers: StateFlow<List<BlockedEntry>> = _blockedUsers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadBlockedUsers()
    }

    fun loadBlockedUsers() {
        viewModelScope.launch {
            _isLoading.value = true
            contactsRepository.getBlockedUsers()
                .onSuccess { _blockedUsers.value = it }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun unblockUser(blockedUserId: String) {
        viewModelScope.launch {
            contactsRepository.unblockUser(blockedUserId)
                .onSuccess {
                    _blockedUsers.value = _blockedUsers.value
                        .filter { it.blockedUserId != blockedUserId }
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun clearError() { _error.value = null }
}