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
import com.example.memegram.data.gallery.AttachItem
import com.example.memegram.data.gallery.guessMimeType
import com.example.memegram.data.gallery.readUploadBytes
import com.example.memegram.mls.decryptMediaBytes
import com.example.memegram.mls.encryptMediaBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var myDeviceId: String? = null
    private var typingJob: Job? = null
    private var sseJob: Job? = null
    private var dbObserveJob: Job? = null
    private var pollingJob: Job? = null

    /**
     * Mutex защищает MLS-рэтчет от гонки между SSE и polling:
     * оба могут получить одно и то же сообщение одновременно,
     * но decrypt должен произойти ровно один раз и строго по порядку.
     */
    private val decryptMutex = Mutex()

    fun loadConversation(conversationId: String) {
        if (currentConversationId == conversationId) return
        currentConversationId = conversationId
        myUserId = sessionManager.getUserId()
        myDeviceId = sessionManager.getDeviceId()
        ActiveChatCoordinator.conversationId = conversationId

        sseJob?.cancel()
        pollingJob?.cancel()
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
            startMessagePolling(conversationId)
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

            val uiMessages = sortedMessages.map { msg ->
                val existing = existingLocalMessages.find { it.serverId == msg.id }

                val isSentByMe = msg.effectiveSenderId == myId

                val text = when {
                    existing != null && existing.text.isNotBlank() && existing.text != "🔒" && !existing.text.startsWith("🔒") -> existing.text

                    else -> try {
                        mlsManager.decrypt(conversationId, msg.mlsCiphertextB64) ?: "🔒 [Зашифровано]"
                    } catch (e: Exception) {
                        if (isSentByMe) "🔒 [Отправлено с другого устройства]" else "🔒 [История]"
                    }
                }

                val isImageMsg = text.startsWith("[image:")
                val (parsedMediaId, imageCaption) = if (isImageMsg) parseImagePayload(text) else Pair("", text)

                Message(
                    id         = existing?.id ?: msg.id.hashCode(),
                    serverId   = msg.id,
                    text       = if (isImageMsg) imageCaption else text,
                    isOutgoing = isSentByMe,
                    timestamp  = msg.createdAt * 1000L,
                    status     = MessageStatus.SENT,
                    type       = if (isImageMsg) "image" else (existing?.type ?: "text"),
                    mediaId    = parsedMediaId.takeIf { it.isNotBlank() } ?: existing?.mediaId
                )
            }

            println("MemegramDebug: Сохраняю ${uiMessages.size} сообщений в БД...")
            chatRepository.saveMessages(uiMessages, conversationId)
            mlsManager.flushState()
            println("MemegramDebug: loadMessages завершен")
        } catch (e: Exception) {
            println("MemegramDebug: Ошибка в loadMessages: ${e.message}")
            _error.value = "Ошибка загрузки: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    // ───────────────────────── SSE ─────────────────────────

    private fun subscribeToEvents(conversationId: String) {
        sseJob = viewModelScope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    println("MemegramDebug [SSE]: подключаемся к $conversationId")
                    api.subscribeToConversation(conversationId).collect { event ->
                        println("MemegramDebug [SSE]: получено событие type=${event.type} convId=${event.conversationId}")
                        handleEvent(conversationId, event)
                        backoffMs = 1_000L
                    }
                    println("MemegramDebug [SSE]: стрим завершился сервером, retry через ${backoffMs}мс")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val isExpectedClose = e.message?.contains("timeout", ignoreCase = true) == true
                            || e.message?.contains("ClosedByteChannelException") == true
                    if (isExpectedClose) {
                        backoffMs = 1_000L
                        println("MemegramDebug [SSE]: таймаут (штатно), переподключаемся через 1с")
                    } else {
                        println("MemegramDebug [SSE]: ошибка (${e.message}), retry через ${backoffMs}мс")
                    }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun handleEvent(convId: String, event: SseEvent) {
        val myId = myUserId ?: return
        val data = event.data ?: return

        when (event.type) {
            "new_message" -> {
                val msgId = data.id ?: return
                println("MemegramDebug [SSE]: new_message msgId=$msgId sender=${data.senderUserId}")

                decryptAndSave(
                    convId        = convId,
                    msgId         = msgId,
                    ciphertextB64 = data.mlsCiphertextB64 ?: "",
                    createdAt     = data.createdAt,
                    source        = "SSE",
                    isOutgoing    = data.senderUserId == myId
                )
            }

            "message_edited" -> {
                val msgId = data.messageId ?: return
                try {
                    val text = mlsManager.decrypt(convId, data.newMlsCiphertextB64 ?: "")
                    mlsManager.flushState()
                    if (text != null) {
                        val existing = _messages.value.find { it.serverId == msgId }
                        if (existing != null) chatRepository.saveMessage(existing.copy(text = text), convId)
                    }
                } catch (_: Exception) {}
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

    // ───────────────────────── Polling fallback ─────────────────────────

    private fun startMessagePolling(conversationId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                pollNewMessages(conversationId)
            }
        }
    }

    private suspend fun pollNewMessages(conversationId: String) {
        try {
            val myId = myUserId ?: return
            val serverMessages = api.getMessages(conversationId)
            val localMessages  = chatRepository.getMessagesOnce(conversationId)
            val localServerIds = localMessages.mapNotNull { it.serverId }.toSet()

            val newMessages = serverMessages
                .filter { it.id !in localServerIds }
                .sortedBy { it.createdAt }

            if (newMessages.isEmpty()) return

            println("MemegramDebug [Poll]: найдено ${newMessages.size} новых сообщений")

            for (msg in newMessages) {
                decryptAndSave(
                    convId        = conversationId,
                    msgId         = msg.id,
                    ciphertextB64 = msg.mlsCiphertextB64,
                    createdAt     = msg.createdAt,
                    source        = "Poll",
                    isOutgoing    = msg.effectiveSenderId == myId
                )
            }
        } catch (e: Exception) {
            println("MemegramDebug [Poll]: ошибка: ${e.message}")
        }
    }

    // ───────────────────────── Общая логика расшифровки ─────────────────────────

    private suspend fun decryptAndSave(
        convId: String,
        msgId: String,
        ciphertextB64: String,
        createdAt: Long,
        source: String,
        isOutgoing: Boolean
    ) {
        decryptMutex.withLock {
            val alreadyExists = chatRepository.getMessagesOnce(convId)
                .any { it.serverId == msgId && it.text.isNotBlank() && it.text != "🔒" }
            if (alreadyExists) {
                println("MemegramDebug [$source]: $msgId уже в БД, пропускаем")
                return@withLock
            }

            val plaintext = try {
                mlsManager.decrypt(convId, ciphertextB64) ?: run {
                    println("MemegramDebug [$source]: decrypt вернул null для $msgId")
                    return@withLock
                }
            } catch (e: Exception) {
                println("MemegramDebug [$source]: decrypt FAILED для $msgId: ${e.message}")
                return@withLock
            }
            mlsManager.flushState()
            println("MemegramDebug [$source]: расшифровано '$plaintext' для $msgId")

            val isImage = plaintext.startsWith("[image:")
            val (mediaId, caption) = if (isImage) parseImagePayload(plaintext) else Pair("", plaintext)

            val msg = Message(
                id         = msgId.hashCode(),
                serverId   = msgId,
                text       = if (isImage) caption else plaintext,
                isOutgoing = isOutgoing,
                timestamp  = createdAt * 1000L,
                status     = MessageStatus.SENT,
                type       = if (isImage) "image" else "text",
                mediaId    = mediaId.ifBlank { null }
            )
            chatRepository.saveMessage(msg, convId)
            println("MemegramDebug [$source]: сохранено в БД ✅")
        }
    }

    // ───────────────────────── MLS sync ─────────────────────────

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
                    println("MemegramDebug: Welcome не найден!")
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
                println("MemegramDebug: Применяем ${commits.size} commits...")
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

    // ───────────────────────── Input & Send ─────────────────────────
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

    fun sendMessage(attachments: List<AttachItem> = emptyList()) {
        val convId = currentConversationId ?: return
        val text = _inputText.value.trim()
        if (text.isBlank() && attachments.isEmpty()) return
        _inputText.value = ""

        viewModelScope.launch {
            when {
                attachments.isEmpty() -> sendTextMessageInternal(convId, text)
                attachments.size == 1 -> sendPhotoMessageInternal(convId, attachments[0], caption = text)
                else -> {
                    sendPhotoMessageInternal(convId, attachments[0], caption = text)
                    attachments.drop(1).forEach { sendPhotoMessageInternal(convId, it, caption = "") }
                }
            }
        }
    }

    private suspend fun sendTextMessageInternal(convId: String, text: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val tempMsg = Message(
            id = now.hashCode(), text = text, isOutgoing = true,
            timestamp = now, status = MessageStatus.SENDING, type = "text"
        )
        chatRepository.saveMessage(tempMsg, convId)

        try {
            if (!mlsManager.hasGroup(convId)) {
                chatRepository.saveMessage(tempMsg.copy(text = "⚠️ Шифрование не готово", status = MessageStatus.FAILED), convId)
                return
            }
            val ciphertextB64 = mlsManager.encrypt(convId, text)
            mlsManager.flushState()
            val response = api.sendMessage(
                conversationId = convId,
                request = SendMessageRequest(
                    mlsCiphertextB64 = ciphertextB64,
                    type = "text",
                    clientMessageId = generateUuid()
                )
            )
            chatRepository.saveMessage(
                tempMsg.copy(serverId = response.messageId, status = MessageStatus.SENT), convId
            )
        } catch (e: Exception) {
            chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED), convId)
            _error.value = "Ошибка отправки: ${e.message}"
        }
    }

    private suspend fun sendPhotoMessageInternal(convId: String, item: AttachItem, caption: String = "") {
        val now = Clock.System.now().toEpochMilliseconds()
        val previewBytes = runCatching { item.readUploadBytes() }.getOrNull()

        val tempMsg = Message(
            id = now.hashCode(), text = caption, isOutgoing = true,
            timestamp = now, status = MessageStatus.SENDING,
            type = "image", localPreviewBytes = previewBytes
        )
        chatRepository.saveMessage(tempMsg, convId)

        try {
            if (!mlsManager.hasGroup(convId)) {
                chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED, text = "⚠️ Шифрование не готово"), convId)
                return
            }

            val rawBytes = previewBytes ?: item.readUploadBytes()
            val mime     = item.guessMimeType()
            val encrypted = encryptMediaBytes(rawBytes)

            val initResp = api.initiateMediaUpload(
                InitiateMediaUploadRequest(
                    conversationId     = convId,
                    mimeType           = mime,
                    encryptedSize      = encrypted.encryptedBytes.size.toLong(),
                    encryptionMetadata = encrypted.encryptionMetadataB64
                )
            )
            api.uploadEncryptedBytesToUrl(initResp.uploadUrl, encrypted.encryptedBytes, mime)
            api.confirmMediaUpload(initResp.mediaId)

            val mlsPayload    = "[image:${initResp.mediaId}]$caption"
            val ciphertextB64 = mlsManager.encrypt(convId, mlsPayload)
            mlsManager.flushState()

            val response = api.sendMessage(
                conversationId = convId,
                request = SendMessageRequest(
                    mlsCiphertextB64 = ciphertextB64,
                    type             = "image",
                    clientMessageId  = generateUuid(),
                    mediaId          = initResp.mediaId
                )
            )

            chatRepository.saveMessage(
                tempMsg.copy(
                    serverId           = response.messageId,
                    status             = MessageStatus.SENT,
                    text               = caption,
                    mediaId            = initResp.mediaId,
                    encryptionMetadata = encrypted.encryptionMetadataB64
                ),
                convId
            )
        } catch (e: Exception) {
            println("MemegramDebug [Photo] ❌ FATAL: ${e::class.simpleName}: ${e.message}")
            chatRepository.saveMessage(
                tempMsg.copy(status = MessageStatus.FAILED, text = "❌ Ошибка отправки фото"), convId
            )
            _error.value = "Ошибка отправки фото: ${e.message}"
        }
    }

    // ───────────────────────── Media ─────────────────────────

    private val _mediaCache = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val mediaCache: StateFlow<Map<String, ByteArray>> = _mediaCache.asStateFlow()

    fun loadMedia(mediaId: String, encryptionMetadata: String?) {
        if (_mediaCache.value.containsKey(mediaId)) return
        viewModelScope.launch {
            try {
                val resp           = api.getMediaDownloadUrl(mediaId)
                val encryptedBytes = api.downloadBytesFromUrl(resp.downloadUrl)
                val meta           = resp.encryptionMetadata.takeIf { it.isNotBlank() } ?: encryptionMetadata
                val decryptedBytes = if (meta != null) decryptMediaBytes(encryptedBytes, meta) else encryptedBytes
                _mediaCache.value += (mediaId to decryptedBytes)
            } catch (e: Exception) {
                println("MemegramDebug [Media] loadMedia $mediaId FAILED: ${e.message}")
            }
        }
    }

    // ───────────────────────── Read ─────────────────────────

    fun markMessagesRead(lastVisibleServerId: String) {
        val convId = currentConversationId ?: return
        val msg = _messages.value.find { it.serverId == lastVisibleServerId } ?: return
        if (msg.isOutgoing) return
        if (lastVisibleServerId == _lastReadServerId) return
        _lastReadServerId = lastVisibleServerId

        viewModelScope.launch {
            runCatching { api.markAsRead(convId, MarkAsReadRequest(lastVisibleServerId)) }
        }
    }

    private var _lastReadServerId: String? = null

    fun clearMessages() {
        val convId = currentConversationId ?: return
        viewModelScope.launch { chatRepository.deleteMessages(convId) }
    }

    // ───────────────────────── Helpers ─────────────────────────

    private fun parseImagePayload(payload: String): Pair<String, String> {
        if (!payload.startsWith("[image:")) return Pair("", payload)
        val closeIdx = payload.indexOf(']')
        if (closeIdx == -1) return Pair("", payload)
        val mediaId = payload.substring(7, closeIdx)
        val caption = payload.substring(closeIdx + 1).trim()
        return Pair(mediaId, caption)
    }

    override fun onCleared() {
        super.onCleared()
        if (ActiveChatCoordinator.conversationId == currentConversationId) {
            ActiveChatCoordinator.conversationId = null
        }
        sseJob?.cancel()
        pollingJob?.cancel()
        typingJob?.cancel()
        dbObserveJob?.cancel()
    }
}