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
    private val contactsRepository: ContactsRepository,
    private val blockedUsersCache: BlockedUsersCache
) : ViewModel() {

    private val _blockedUsers = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val blockedUsers: StateFlow<List<BlockedEntry>> = _blockedUsers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private val pageSize = 50

    init {
        loadBlockedUsers()
    }

    fun loadBlockedUsers() {
        viewModelScope.launch {
            _isLoading.value = true
            contactsRepository.getBlockedUsers(limit = pageSize, offset = 0)
                .onSuccess {
                    _blockedUsers.value = it
                    _canLoadMore.value = it.size >= pageSize
                }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value || !_canLoadMore.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            val offset = _blockedUsers.value.size
            contactsRepository.getBlockedUsers(limit = pageSize, offset = offset)
                .onSuccess { page ->
                    _blockedUsers.value = _blockedUsers.value + page
                    _canLoadMore.value = page.size >= pageSize
                }
                .onFailure { _error.value = it.message }
            _isLoadingMore.value = false
        }
    }

    fun unblockUser(blockedUserId: String) {
        viewModelScope.launch {
            contactsRepository.unblockUser(blockedUserId)
                .onSuccess {
                    _blockedUsers.value = _blockedUsers.value
                        .filter { it.blockedUserId != blockedUserId }
                    blockedUsersCache.remove(blockedUserId)
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun clearError() { _error.value = null }
}
