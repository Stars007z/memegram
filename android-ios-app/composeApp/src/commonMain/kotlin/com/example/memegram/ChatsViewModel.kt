package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class ChatsViewModel(sessionManager: SessionManager) : ViewModel() {

    private val _sessionManager = sessionManager

    private val _allChats = MutableStateFlow(
        listOf(
            ChatModel(id = 1, name = "Denis",       lastMessage = "👋",      timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()),
            ChatModel(id = 2, name = "Skibob",      lastMessage = "Привет!", timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()),
            ChatModel(id = 3, name = "DSBA233",     lastMessage = "!!!",     timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()),
            ChatModel(id = 4, name = "HACKERSHOP",  lastMessage = "...",     timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()),
        )
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val chats: StateFlow<List<ChatModel>> = combine(_allChats, _searchQuery) { chats, query ->
        if (query.isBlank()) chats
        else chats.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.lastMessage.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _allChats.value)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun logout() {
        _sessionManager.clear()
    }
}