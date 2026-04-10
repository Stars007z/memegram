package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.audio.AudioRecordResult
import com.example.memegram.audio.createAudioPlayer
import com.example.memegram.audio.createAudioRecorder
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

    private val _isGroupChat = MutableStateFlow(false)
    val isGroupChat: StateFlow<Boolean> = _isGroupChat.asStateFlow()

    private val _messageSenders = MutableStateFlow<Map<String, String>>(emptyMap())
    val messageSenders: StateFlow<Map<String, String>> = _messageSenders.asStateFlow()

    private val _memberProfiles = MutableStateFlow<Map<String, UserProfileResponse>>(emptyMap())
    val memberProfiles: StateFlow<Map<String, UserProfileResponse>> = _memberProfiles.asStateFlow()

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

    var currentConversationId: String? = null
        private set
    var peerUserId: String? = null
        private set

    val audioRecorder = createAudioRecorder()
    val audioPlayer = createAudioPlayer()
    private var myUserId: String? = null
    private var myDeviceId: String? = null
    private var typingJob: Job? = null
    private var sseJob: Job? = null
    private var dbObserveJob: Job? = null
    private var pollingJob: Job? = null

    private val decryptMutex = Mutex()

    enum class RecordState { IDLE, HOLDING, LOCKED, PAUSED }
    private val _recordState = MutableStateFlow(RecordState.IDLE)
    val recordState: StateFlow<RecordState> = _recordState.asStateFlow()

    private val _voiceAmplitudes = MutableStateFlow<List<Int>>(emptyList())
    val voiceAmplitudes: StateFlow<List<Int>> = _voiceAmplitudes.asStateFlow()

    private val _voiceDurationMs = MutableStateFlow(0L)
    val voiceDurationMs: StateFlow<Long> = _voiceDurationMs.asStateFlow()

    private var recordTimerJob: Job? = null
    private var rawAmplitudes = mutableListOf<Int>()

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
                val isGroup = conv.type != "direct"
                _isGroupChat.value = isGroup

                if (isGroup) {
                    conv.members.forEach { member ->
                        if (member.userId != myUserId && !_memberProfiles.value.containsKey(member.userId)) {
                            launch {
                                try {
                                    val profile = api.getUserById(member.userId)
                                    _memberProfiles.update { it + (member.userId to profile) }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } else {
                    val peer = conv.members.find { it.userId != myUserId }
                    peerUserId = peer?.userId
                }
            } catch (_: Exception) { }

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
        try {
            val rawMessages = api.getMessages(conversationId)
            val myId = myUserId ?: ""
            val sortedMessages = rawMessages.sortedBy { it.createdAt }
            decryptMutex.withLock {
                val existingLocalMessages = chatRepository.getMessagesOnce(conversationId)
                val newSenders = mutableMapOf<String, String>()

                val uiMessages = sortedMessages.map { msg ->
                    val existing = existingLocalMessages.find { it.serverId == msg.id }
                    val isSentByMe = msg.effectiveSenderId == myId
                    newSenders[msg.id] = msg.effectiveSenderId

                    val text = when {
                        existing != null && !existing.text.startsWith("🔒") && (existing.text.isNotBlank() || existing.type != "text") -> existing.text
                        else -> try {
                            mlsManager.decrypt(conversationId, msg.mlsCiphertextB64) ?: "🔒 [Зашифровано]"
                        } catch (_: Exception) {
                            if (isSentByMe) "🔒 [Отправлено с другого устройства]" else "🔒 [Ошибка расшифровки]"
                        }
                    }

                    val (parsedType, parsedMediaId, content) = parseMlsPayload(text)

                    Message(
                        id         = existing?.id ?: msg.id.hashCode(),
                        serverId   = msg.id,
                        text       = content,
                        isOutgoing = isSentByMe,
                        timestamp  = msg.createdAt * 1000L,
                        status     = MessageStatus.SENT,
                        type       = if (parsedType != "text") parsedType else (existing?.type ?: "text"),
                        mediaId    = parsedMediaId.takeIf { it.isNotBlank() } ?: existing?.mediaId
                    )
                }

                _messageSenders.update { it + newSenders }
                chatRepository.saveMessages(uiMessages, conversationId)
                mlsManager.flushState()
            }
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
                    api.subscribeToConversation(conversationId).collect { event ->
                        handleEvent(conversationId, event)
                        backoffMs = 1_000L
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val isExpectedClose = e.message?.contains("timeout", ignoreCase = true) == true
                            || e.message?.contains("ClosedByteChannelException") == true
                    if (isExpectedClose) {
                        backoffMs = 1_000L
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
                data.senderUserId?.let { senderId ->
                    _messageSenders.update { it + (msgId to senderId) }
                }
                decryptAndSave(
                    convId        = convId,
                    msgId         = msgId,
                    ciphertextB64 = data.mlsCiphertextB64 ?: "",
                    createdAt     = data.createdAt,
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
            val localServerIds = localMessages.map { it.serverId }.toSet()

            val newMessages = serverMessages
                .filter { it.id !in localServerIds }
                .sortedBy { it.createdAt }

            if (newMessages.isEmpty()) return

            println("MemegramDebug [Poll]: найдено ${newMessages.size} новых сообщений")

            val newSenders = mutableMapOf<String, String>()
            for (msg in newMessages) {
                newSenders[msg.id] = msg.effectiveSenderId
                decryptAndSave(
                    convId        = conversationId,
                    msgId         = msg.id,
                    ciphertextB64 = msg.mlsCiphertextB64,
                    createdAt     = msg.createdAt,
                    isOutgoing    = msg.effectiveSenderId == myId
                )
            }
            _messageSenders.update { it + newSenders }
        } catch (_: Exception) { }
    }

    // ───────────────────────── General logic of decryption ─────────────────────────

    private suspend fun decryptAndSave(
        convId: String,
        msgId: String,
        ciphertextB64: String,
        createdAt: Long,
        isOutgoing: Boolean
    ) {
        decryptMutex.withLock {
            val alreadyExists = chatRepository.getMessagesOnce(convId)
                .any { it.serverId == msgId && it.text.isNotBlank() && it.text != "🔒" }
            if (alreadyExists) return@withLock

            val plaintext = try {
                mlsManager.decrypt(convId, ciphertextB64) ?: return@withLock
            } catch (_: Exception) { return@withLock }

            mlsManager.flushState()

            val (parsedType, parsedMediaId, content) = parseMlsPayload(plaintext)

            val msg = Message(
                id         = msgId.hashCode(),
                serverId   = msgId,
                text       = content,
                isOutgoing = isOutgoing,
                timestamp  = createdAt * 1000L,
                status     = MessageStatus.SENT,
                type       = if (parsedType != "text") parsedType else "text",
                mediaId    = parsedMediaId.takeIf { it.isNotBlank() }
            )
            chatRepository.saveMessage(msg, convId)
        }
    }

    // ───────────────────────── MLS sync ─────────────────────────

    private suspend fun syncMlsPending(conversationId: String): Boolean {
        var justProcessedWelcome = false

        if (!mlsManager.hasGroup(conversationId)) {
            try {
                val welcomes = api.getPendingWelcomes()
                val welcome = welcomes.find { it.conversationId == conversationId }
                if (welcome != null) {
                    mlsManager.processWelcome(conversationId, welcome.welcomeDataB64)
                    api.ackWelcome(welcome.id)
                    justProcessedWelcome = true
                } else {
                    return false
                }
            } catch (_: Exception) { return false }
        }

        try {
            if (justProcessedWelcome) {
                val realEpoch = mlsManager.getRealMlsEpoch(conversationId)
                mlsManager.updateGroupEpoch(conversationId, realEpoch)
                println("MemegramDebug [Welcome]: Синхронизирована metadata-эпоха с реальной MLS = $realEpoch")
                return true
            }

            val localEpoch = mlsManager.getGroupEpoch(conversationId)
            val commits = api.getPendingCommits(conversationId, localEpoch)
            val newCommits = commits.filter { it.epoch > localEpoch }

            if (newCommits.isNotEmpty()) {
                newCommits.sortedBy { it.epoch }.forEach { commit ->
                    val success = try {
                        mlsManager.processCommit(conversationId, commit.commitDataB64)
                    } catch (e: Exception) { false }

                    if (success) {
                        mlsManager.updateGroupEpoch(conversationId, commit.epoch)
                    }
                }
                mlsManager.flushState()
            }
        } catch (_: Exception) { return false }

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

    fun sendVoiceMessageInternal(convId: String, recordResult: AudioRecordResult) {
        println("MemegramDebug [Voice]: Старт sendVoiceMessageInternal для $convId")
        val now = Clock.System.now().toEpochMilliseconds()

        val tempMsg = Message(
            id = now.hashCode(),
            text = recordResult.durationMs.toString(),
            isOutgoing = true,
            timestamp = now,
            status = MessageStatus.SENDING,
            type = "voice"
        )
        viewModelScope.launch {
            chatRepository.saveMessage(tempMsg, convId)

            try {
                if (!mlsManager.hasGroup(convId)) {
                    chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED, text = "⚠️ Шифрование не готово"), convId)
                    return@launch
                }

                val encrypted = encryptMediaBytes(recordResult.bytes)
                println("MemegramDebug [Voice]: Данные зашифрованы. Размер: ${encrypted.encryptedBytes.size} байт")

                val initResp = api.initiateMediaUpload(
                    InitiateMediaUploadRequest(
                        conversationId     = convId,
                        mimeType           = "audio/mp4",
                        encryptedSize      = encrypted.encryptedBytes.size.toLong(),
                        encryptionMetadata = encrypted.encryptionMetadataB64
                    )
                )
                println("MemegramDebug [Voice]: Получен URL для загрузки: ${initResp.mediaId}")
                api.uploadEncryptedBytesToUrl(initResp.uploadUrl, encrypted.encryptedBytes, "audio/mp4")
                println("MemegramDebug [Voice]: Байты улетели на 'S3'")
                api.confirmMediaUpload(initResp.mediaId)
                println("MemegramDebug [Voice]: ✅ Голосовое сообщение успешно отправлено!")

                val mlsPayload = "[voice:${initResp.mediaId}:${recordResult.waveform}]${recordResult.durationMs}"
                val ciphertextB64 = mlsManager.encrypt(convId, mlsPayload)
                mlsManager.flushState()

                val response = api.sendMessage(
                    conversationId = convId,
                    request = SendMessageRequest(
                        mlsCiphertextB64 = ciphertextB64,
                        type             = "voice",
                        clientMessageId  = generateUuid(),
                        mediaId          = initResp.mediaId
                    )
                )

                chatRepository.saveMessage(
                    tempMsg.copy(
                        serverId           = response.messageId,
                        status             = MessageStatus.SENT,
                        mediaId            = initResp.mediaId,
                        encryptionMetadata = encrypted.encryptionMetadataB64,
                        text               = "${recordResult.durationMs}|${recordResult.waveform}"
                    ),
                    convId
                )
            } catch (e: Exception) {
                println("MemegramDebug [Voice]: 🚨 КРИТИЧЕСКАЯ ОШИБКА ОТПРАВКИ: ${e.message}")
                chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED), convId)
                _error.value = "Ошибка отправки голосового: ${e.message}"
            }
        }
    }

    fun startVoiceRecording() {
        if (_recordState.value != RecordState.IDLE) return
        _recordState.value = RecordState.HOLDING
        rawAmplitudes.clear()
        _voiceAmplitudes.value = emptyList()
        _voiceDurationMs.value = 0L

        audioRecorder.startRecording()

        recordTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(50)
                if (_recordState.value == RecordState.PAUSED) continue

                val amp = audioRecorder.getMaxAmplitude()
                val normalized = ((amp / 32767f) * 9).toInt().coerceIn(0, 9)
                rawAmplitudes.add(normalized)

                _voiceAmplitudes.value = rawAmplitudes.toList()
                _voiceDurationMs.value += 50L
            }
        }
    }

    fun lockVoiceRecording() { _recordState.value = RecordState.LOCKED }

    fun pauseVoiceRecording() {
        if (_recordState.value == RecordState.LOCKED) {
            audioRecorder.pauseRecording()
            _recordState.value = RecordState.PAUSED
        }
    }

    fun resumeVoiceRecording() {
        if (_recordState.value == RecordState.PAUSED) {
            audioRecorder.resumeRecording()
            _recordState.value = RecordState.LOCKED
        }
    }

    fun cancelVoiceRecording() {
        recordTimerJob?.cancel()
        audioRecorder.cancelRecording()
        _recordState.value = RecordState.IDLE
        rawAmplitudes.clear()
        _voiceAmplitudes.value = emptyList()
        _voiceDurationMs.value = 0L
    }

    fun stopAndSendVoiceMessage() {
        println("MemegramDebug [Voice]: Начинаем остановку записи...")
        recordTimerJob?.cancel()
        val finalDurationMs = _voiceDurationMs.value
        _recordState.value = RecordState.IDLE

        println("MemegramDebug [Voice]: Собранных амплитуд: ${rawAmplitudes.size}, длительность: ${finalDurationMs}мс")

        val waveformStr = downsampleWaveform(rawAmplitudes, 40)
        println("MemegramDebug [Voice]: Waveform сжат: $waveformStr")

        val result = audioRecorder.stopRecording(waveformStr)

        if (result == null) {
            println("MemegramDebug [Voice]: 🚨 ОШИБКА: Рекордер вернул null. Возможно, файл не создался или MediaRecorder упал.")
        }

        if (result != null && finalDurationMs > 1000) {
            println("MemegramDebug [Voice]: Подготовка к шифрованию и отправке. Байт: ${result.bytes.size}")
            val adjustedResult = result.copy(durationMs = finalDurationMs)
            currentConversationId?.let { sendVoiceMessageInternal(it, adjustedResult) }
        } else if (result != null) {
            println("MemegramDebug [Voice]: Запись слишком короткая, отмена.")
        }
    }

    private fun downsampleWaveform(amps: List<Int>, targetSize: Int): String {
        if (amps.isEmpty()) return ""
        val result = StringBuilder()
        val chunkSize = maxOf(1, amps.size / targetSize)
        for (i in 0 until targetSize) {
            val start = i * chunkSize
            if (start >= amps.size) break
            val end = minOf(start + chunkSize, amps.size)
            val avg = amps.subList(start, end).average().toInt().coerceIn(0, 9)
            result.append(avg)
        }
        return result.toString()
    }

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
            } catch (_: Exception) { }
        }
    }

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

    private fun parseMlsPayload(payload: String): Triple<String, String, String> {
        if (payload.startsWith("[image:")) {
            val closeIdx = payload.indexOf(']')
            if (closeIdx == -1) return Triple("text", "", payload)
            val mediaId = payload.substring(7, closeIdx)
            val caption = payload.substring(closeIdx + 1).trim()
            return Triple("image", mediaId, caption)
        }
        if (payload.startsWith("[voice:")) {
            val closeIdx = payload.indexOf(']')
            if (closeIdx == -1) return Triple("text", "", payload)

            val metaInfo = payload.substring(7, closeIdx).split(":")
            val mediaId = metaInfo[0]
            val waveform = if (metaInfo.size > 1) metaInfo[1] else ""
            val durationMs = payload.substring(closeIdx + 1).trim()

            return Triple("voice", mediaId, "$durationMs|$waveform")
        }
        return Triple("text", "", payload)
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