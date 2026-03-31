package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager
import com.example.memegram.utils.generateUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

class ChatViewModel(
    private val api: ApiService,
    private val sessionManager: SessionManager,
    private val mlsManager: MlsManager,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _inputText        = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _chatBgColor      = MutableStateFlow(Color(0xFFECECEC))
    val chatBgColor: StateFlow<Color> = _chatBgColor.asStateFlow()

    private val _myBubbleColor    = MutableStateFlow(Color(0xFF4CAF50))
    val myBubbleColor: StateFlow<Color> = _myBubbleColor.asStateFlow()

    private val _theirBubbleColor = MutableStateFlow(Color(0xFFFFFFFF))
    val theirBubbleColor: StateFlow<Color> = _theirBubbleColor.asStateFlow()

    private val _isLoading        = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error            = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _chatTitle = MutableStateFlow("Загрузка...")
    val chatTitle: StateFlow<String> = _chatTitle.asStateFlow()

    private val _chatAvatarId = MutableStateFlow<String?>(null)
    val chatAvatarId: StateFlow<String?> = _chatAvatarId.asStateFlow()

    private var currentConversationId: String? = null
    private var myUserId: String? = null
    private var typingJob: Job? = null
    private var sseJob: Job? = null
    private var dbObserveJob: Job? = null

    fun loadConversation(conversationId: String) {
        if (currentConversationId == conversationId) return
        currentConversationId = conversationId
        myUserId = sessionManager.getUserId()

        sseJob?.cancel()
        dbObserveJob?.cancel()

        dbObserveJob = viewModelScope.launch {
            chatRepository.getMessagesFlow(conversationId).collect { msgs ->
                _messages.value = msgs.sortedBy { it.timestamp }
            }
        }

        viewModelScope.launch {
            try {
                val conv = api.getConversation(conversationId)
                if (conv.type == "direct") {
                    val peer = conv.members.find { it.userId != myUserId }
                    if (peer != null) {
                        val profile = api.getUserById(peer.userId)
                        _chatTitle.value = profile.username?.takeIf { it.isNotBlank() } ?: "Собеседник"
                        _chatAvatarId.value = profile.avatarMediaId
                    }
                } else {
                    _chatTitle.value = conv.name ?: "Группа"
                }
            } catch (e: Exception) {
                _chatTitle.value = "Собеседник"
            }

            val mlsReady = syncMlsPending(conversationId)
            if (!mlsReady) {
                _error.value = "MLS не готов для этого чата"
                return@launch
            }

            loadMessages(conversationId)
            subscribeToEvents(conversationId)
        }
    }


    private suspend fun loadMessages(conversationId: String) {
        _isLoading.value = true
        println("MemegramDebug: loadMessages вызван для чата $conversationId")
        try {
            val rawMessages = api.getMessages(conversationId)
            println("MemegramDebug: Получено ${rawMessages.size} сообщений с сервера")

            val myId = myUserId ?: ""
            val sortedMessages = rawMessages.sortedBy { it.createdAt }
            val existingLocalMessages = chatRepository.getMessagesOnce(conversationId)

            val uiMessages = sortedMessages.mapNotNull { msg ->
                val existing = existingLocalMessages.find { it.serverId == msg.id }

                if (msg.effectiveSenderId == myId && (existing == null || existing.text.isBlank())) {
                    return@mapNotNull null
                }

                val text = when {
                    existing != null
                            && existing.text.isNotBlank()
                            && existing.text != "🔒"
                            && !existing.text.startsWith("[Ошибка") -> existing.text

                    msg.effectiveSenderId == myId -> existing!!.text

                    else -> try {
                        mlsManager.decrypt(conversationId, msg.mlsCiphertextB64) ?: "🔒 [Зашифровано]"
                    } catch (e: Exception) {
                        "🔒 [История]"
                    }
                }

                Message(
                    id         = existing?.id ?: msg.id.hashCode(),
                    serverId   = msg.id,
                    text       = text,
                    isOutgoing = msg.effectiveSenderId == myId,
                    timestamp  = msg.createdAt * 1000L,
                    status     = MessageStatus.SENT
                )
            }

            println("MemegramDebug: Сохраняю ${uiMessages.size} сообщений в БД...")
            chatRepository.saveMessages(uiMessages, conversationId)

            mlsManager.flushState()
            println("MemegramDebug: flushState выполнен")

            val lastServerId = sortedMessages.lastOrNull()?.id ?: ""
            if (lastServerId.isNotBlank()) {
                runCatching { api.markAsRead(conversationId, MarkAsReadRequest(lastServerId)) }
            }
        } catch (e: Exception) {
            println("MemegramDebug: Ошибка в loadMessages: ${e.message}")
            _error.value = "Ошибка загрузки: ${e.message}"
        } finally {
            _isLoading.value = false
            println("MemegramDebug: loadMessages завершен")
        }
    }

    private suspend fun syncMlsPending(conversationId: String): Boolean {
        if (!mlsManager.hasGroup(conversationId)) {
            try {
                val welcomes = api.getPendingWelcomes()
                val welcome = welcomes.find { it.conversationId == conversationId }
                if (welcome != null) {
                    println("MemegramDebug: Применяем Welcome для $conversationId")
                    mlsManager.processWelcome(conversationId, welcome.welcomeDataB64)
                    api.ackWelcome(welcome.id)
                } else {
                    println("MemegramDebug: Welcome не найден, хотя группы локально нет!")
                    return false
                }
            } catch (e: Exception) {
                println("MemegramDebug: Ошибка обработки Welcome: ${e.message}")
                return false
            }
        }

        try {
            val sinceEpoch = mlsManager.getGroupEpoch(conversationId)
            val commits = api.getPendingCommits(conversationId, sinceEpoch)
            if (commits.isNotEmpty()) {
                println("MemegramDebug: Найдено ${commits.size} pending commits. Применяем...")
                commits.sortedBy { it.epoch }.forEach { commit ->
                    mlsManager.processCommit(conversationId, commit.commitDataB64)
                    mlsManager.updateGroupEpoch(conversationId, commit.epoch)
                }
                mlsManager.flushState()
            }
        } catch (e: Exception) {
            println("MemegramDebug: Ошибка обработки Commits: ${e.message}")
            return false
        }

        return true
    }

    fun updateInput(text: String) {
        _inputText.value = text
        sendTypingIndicator()
    }

    private fun sendTypingIndicator() {
        val convId = currentConversationId ?: return
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            runCatching { api.setTyping(convId) }
        }
    }

    fun sendMessage() {
        val convId = currentConversationId ?: return
        val text = _inputText.value.trim()
        if (text.isBlank()) return

        val now = Clock.System.now().toEpochMilliseconds()
        _inputText.value = ""

        viewModelScope.launch {
            val tempMsg = Message(
                id = now.hashCode(),
                text = text,
                isOutgoing = true,
                timestamp = now,
                status = MessageStatus.SENDING
            )
            chatRepository.saveMessage(tempMsg, convId)

            try {
                if (!mlsManager.hasGroup(convId)) {
                    chatRepository.saveMessage(tempMsg.copy(text = "⚠️ Шифрование не готово", status = MessageStatus.FAILED), convId)
                    return@launch
                }

                val ciphertextB64 = mlsManager.encrypt(convId, text)
                mlsManager.flushState()

                val requestUuid = generateUuid()

                val response = api.sendMessage(
                    conversationId = convId,
                    request = SendMessageRequest(
                        mlsCiphertextB64 = ciphertextB64,
                        type = "text",
                        clientMessageId = requestUuid
                    )
                )

                chatRepository.saveMessage(tempMsg.copy(serverId = response.id, status = MessageStatus.SENT), convId)

            } catch (e: Exception) {
                chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED), convId)
                _error.value = "Ошибка отправки: ${e.message}"
            }
        }
    }

    fun markMessagesRead(lastVisibleServerId: String) {
        val convId = currentConversationId ?: return
        if (lastVisibleServerId == _lastReadServerId) return
        _lastReadServerId = lastVisibleServerId

        viewModelScope.launch {
            runCatching {
                api.markAsRead(convId, MarkAsReadRequest(lastVisibleServerId))
            }
        }
    }

    private var _lastReadServerId: String? = null

    fun clearMessages() {
        val convId = currentConversationId ?: return
        viewModelScope.launch {
            chatRepository.deleteMessages(convId)
        }
    }

    private fun subscribeToEvents(conversationId: String) {
        sseJob = viewModelScope.launch {
            try {
                api.subscribeToConversation(conversationId).collect { event ->
                    handleEvent(conversationId, event)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleEvent(convId: String, event: SseEvent) {
        val myId = myUserId ?: return
        val data = event.data ?: return

        when (event.type) {
            "new_message" -> {
                val msgId = data.id ?: return

                if (data.senderUserId == myId) {
                    return
                }

                val text = try {
                    mlsManager.decrypt(convId, data.mlsCiphertextB64 ?: "") ?: "🔒"
                } catch (e: Exception) {
                    println("MemegramDebug: Ошибка SSE дешифровки: ${e.message}")
                    "🔒"
                }

                mlsManager.flushState()

                val newMsg = Message(
                    id         = msgId.hashCode(),
                    serverId   = msgId,
                    text       = text,
                    isOutgoing = false,
                    timestamp = data.createdAt * 1000L,
                    status     = MessageStatus.SENT
                )

                chatRepository.saveMessage(newMsg, convId)
                runCatching { api.markAsRead(convId, MarkAsReadRequest(msgId)) }
            }
            "message_edited" -> {
                val msgId = data.messageId ?: return
                val text = mlsManager.decrypt(convId, data.newMlsCiphertextB64 ?: "")
                mlsManager.flushState()

                if (text != null) {
                    val existing = _messages.value.find { it.serverId == msgId }
                    if (existing != null) {
                        chatRepository.saveMessage(existing.copy(text = text), convId)
                    }
                }
            }
            "message_deleted" -> {
                val msgId = data.messageId ?: return
                val existing = _messages.value.find { it.serverId == msgId }
                if (existing != null) {
                    chatRepository.saveMessage(existing.copy(text = "🗑 Сообщение удалено"), convId)
                }
            }
            "epoch_changed" -> syncMlsPending(convId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        typingJob?.cancel()
        dbObserveJob?.cancel()
    }
}