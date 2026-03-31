package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.LogoutRequest
import com.example.memegram.data.models.SseEvent
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager
import com.example.memegram.mls.MlsManager.Companion.BATCH_KEY_PACKAGES
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ChatsViewModel(
    private val sessionManager: SessionManager,
    private val api: ApiService,
    private val mlsManager: MlsManager,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    val chats: StateFlow<List<ChatModel>> = combine(
        chatRepository.getAllChatsFlow(),
        _searchQuery
    ) { allChats, query ->
        if (query.isBlank()) allChats
        else allChats.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.lastMessage.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var sseJob: Job? = null
    private var pollingJob: Job? = null
    private val profileCache = mutableMapOf<String, com.example.memegram.data.models.UserProfileResponse>()

    init {
        viewModelScope.launch {
            initMls()
            loadChatsInternal()
            startPolling()
        }
    }

    private suspend fun initMls() {
        try {
            mlsManager.initialize()
            if (mlsManager.needsKeyPackages()) {
                val packages = mlsManager.generateKeyPackages(BATCH_KEY_PACKAGES)
                api.uploadKeyPackages(packages)
            }
            processPendingWelcomes()
        } catch (e: Exception) {
            _error.value = "Ошибка инициализации шифрования"
        }
    }

    fun loadChats() {
        viewModelScope.launch { loadChatsInternal() }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                processPendingWelcomes()
                loadChatsInternal(silent = true)
            }
        }
    }

    private suspend fun loadChatsInternal(silent: Boolean = false) {
        if (!silent && chats.value.isEmpty()) _isLoading.update { true }
        if (!silent) _error.value = null

        try {
            val response = api.getConversations()
            val currentUserId = sessionManager.getUserId()

            val newChatsList = response.items.map { conv ->
                var chatName = conv.name?.takeIf { it.isNotBlank() } ?: "Собеседник"

                if (conv.type == "direct") {
                    try {
                        val details = api.getConversation(conv.id)
                        val peer = details.members.find { it.userId != currentUserId }
                        if (peer != null) {
                            val profile = profileCache[peer.userId]
                                ?: api.getUserById(peer.userId).also { profileCache[peer.userId] = it }

                            chatName = profile.username?.takeIf { it.isNotBlank() } ?: "User_${peer.userId.take(4)}"
                        }
                    } catch (_: Exception) {}
                }

                val localLastMessageText = chatRepository.getLastMessageText(conv.id)

                val displayLastMessage = when {
                    localLastMessageText != null -> localLastMessageText
                    conv.lastMessageType == "text" -> "Сообщение"
                    conv.lastMessageType == "image" -> "📸 Фото"
                    conv.lastMessageType == null || conv.lastMessageType == "" -> "Новый чат"
                    else -> conv.lastMessageType
                }

                ChatModel(
                    id = conv.id.hashCode(),
                    conversationId = conv.id,
                    name = chatName,
                    lastMessage = displayLastMessage,
                    timestamp = conv.lastActivityAt * 1000,
                    unreadCount = conv.unreadCount
                )
            }

            chatRepository.saveChats(newChatsList)

            subscribeToGlobalEvents(newChatsList.map { it.conversationId })

        } catch (e: Exception) {
            if (!silent) _error.value = "Не удалось загрузить чаты"
        } finally {
            if (!silent) _isLoading.value = false
        }
    }

    private suspend fun processPendingWelcomes() {
        try {
            val welcomes = api.getPendingWelcomes()
            var hasNew = false
            for (w in welcomes) {
                if (!mlsManager.hasGroup(w.conversationId)) {
                    mlsManager.processWelcome(w.conversationId, w.welcomeDataB64)
                    api.ackWelcome(w.id)
                    hasNew = true
                }
            }
            if (hasNew) loadChatsInternal(silent = true)
        } catch (_: Exception) {}
    }

    private fun subscribeToGlobalEvents(conversationIds: List<String>) {
        sseJob?.cancel()
        if (conversationIds.isEmpty()) return

        sseJob = viewModelScope.launch {
            try {
                api.subscribeToConversation(conversationIds.joinToString(",")).collect { event ->
                    handleGlobalEvent(event)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleGlobalEvent(event: SseEvent) {
        val convId = event.conversationId
        if (convId.isBlank()) return

        when (event.type) {
            "new_message" -> {
                val currentUserId = sessionManager.getUserId()
                val isMine = event.data?.senderUserId == currentUserId

                val decryptedText = mlsManager.decrypt(convId, event.data?.mlsCiphertextB64 ?: "")
                if (decryptedText != null) {
                    mlsManager.flushState()
                    chatRepository.saveMessage(
                        Message(
                            id = event.data?.id.hashCode(),
                            serverId = event.data?.id ?: "",
                            text = decryptedText,
                            isOutgoing = isMine,
                            timestamp = (event.data?.createdAt?.let { it * 1000L }) ?: Clock.System.now().toEpochMilliseconds(),
                            status = MessageStatus.SENT
                        ),
                        convId
                    )
                }

                val chat = chatRepository.getChatById(convId)
                if (chat != null) {
                    chatRepository.saveChat(
                        chat.copy(
                            lastMessage = decryptedText ?: if (isMine) "📨" else "🔒",
                            timestamp = (event.data?.createdAt?.let { it * 1000L }) ?: chat.timestamp,
                            unreadCount = if (isMine) chat.unreadCount else chat.unreadCount + 1
                        )
                    )
                } else {
                    loadChatsInternal(silent = true)
                }
            }
        }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                sessionManager.getAccessToken()?.let { api.logout(LogoutRequest(it)) }
            } catch (_: Exception) {}
            finally {
                pollingJob?.cancel()
                sseJob?.cancel()
                mlsManager.clearAll()
                sessionManager.clear()
                onDone()
            }
        }
    }
}