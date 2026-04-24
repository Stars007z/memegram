package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.notifications.NotificationPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val privateChats: List<ChatModel> = emptyList(),
    val groups: List<ChatModel> = emptyList(),
)

class NotificationsViewModel(
    private val chatRepository: ChatRepository,
    private val prefs: NotificationPrefs,
) : ViewModel() {

    val uiState: StateFlow<NotificationsUiState> =
        chatRepository.getAllChatsFlow()
            .map { chats ->
                NotificationsUiState(
                    privateChats = chats.filter { !it.isGroup }
                        .sortedBy { it.name.lowercase() },
                    groups = chats.filter { it.isGroup }
                        .sortedBy { it.name.lowercase() },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NotificationsUiState(),
            )

    val previewEnabled: StateFlow<Boolean> = prefs.previewEnabled
    val vibrationStrength: StateFlow<Int> = prefs.vibrationStrength

    fun setPreviewEnabled(enabled: Boolean) = prefs.setPreviewEnabled(enabled)
    fun setVibrationStrength(strength: Int) = prefs.setVibrationStrength(strength)

    fun toggleMute(conversationId: String, currentMuteUntil: Long) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val newValue = if (currentMuteUntil > now) 0L else Long.MAX_VALUE
        viewModelScope.launch {
            chatRepository.setMuteUntil(conversationId, newValue)
        }
    }

    fun muteForHours(conversationId: String, hours: Int) {
        if (hours <= 0) return
        val until = kotlin.time.Clock.System.now().toEpochMilliseconds() + hours * 3_600_000L
        viewModelScope.launch {
            chatRepository.setMuteUntil(conversationId, until)
        }
    }

    fun unmute(conversationId: String) {
        viewModelScope.launch {
            chatRepository.setMuteUntil(conversationId, 0L)
        }
    }

    fun muteAllPrivate() {
        val ids = uiState.value.privateChats.map { it.conversationId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chatRepository.setMuteUntilForIds(ids, Long.MAX_VALUE)
        }
    }

    fun unmuteAllPrivate() {
        val ids = uiState.value.privateChats.map { it.conversationId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chatRepository.setMuteUntilForIds(ids, 0L)
        }
    }

    fun muteAllGroups() {
        val ids = uiState.value.groups.map { it.conversationId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chatRepository.setMuteUntilForIds(ids, Long.MAX_VALUE)
        }
    }

    fun unmuteAllGroups() {
        val ids = uiState.value.groups.map { it.conversationId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chatRepository.setMuteUntilForIds(ids, 0L)
        }
    }
}
