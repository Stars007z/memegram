package com.example.memegram

import com.example.memegram.data.repository.ContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BlockedUsersCache(
    private val contactsRepository: ContactsRepository
) {
    private val _blockedIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedIds: StateFlow<Set<String>> = _blockedIds.asStateFlow()

    suspend fun load() {
        val ids = mutableSetOf<String>()
        var offset = 0
        val limit = 50
        while (true) {
            val page = contactsRepository.getBlockedUsers(limit, offset).getOrNull() ?: break
            if (page.isEmpty()) break
            ids += page.map { it.blockedUserId }
            if (page.size < limit) break
            offset += limit
        }
        _blockedIds.value = ids
    }

    fun add(userId: String) {
        _blockedIds.value = _blockedIds.value + userId
    }

    fun remove(userId: String) {
        _blockedIds.value = _blockedIds.value - userId
    }

    fun isBlocked(userId: String): Boolean = userId in _blockedIds.value
}
