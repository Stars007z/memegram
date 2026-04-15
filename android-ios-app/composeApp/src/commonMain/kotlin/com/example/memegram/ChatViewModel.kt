package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.audio.AudioRecordResult
import com.example.memegram.audio.createAudioRecorder
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager
import com.example.memegram.utils.generateUuid
import com.example.memegram.data.gallery.AttachItem
import com.example.memegram.data.gallery.guessMimeType
import com.example.memegram.data.gallery.readUploadBytes
import com.example.memegram.mls.decryptMediaBytes
import com.example.memegram.mls.encryptMediaBytes
import com.example.memegram.localization.S
import com.example.memegram.translation.TranslationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

@OptIn(ExperimentalEncodingApi::class)
class ChatViewModel(
    private val api: ApiService,
    private val sessionManager: SessionManager,
    private val mlsManager: MlsManager,
    private val chatRepository: ChatRepository,
    private val themePreferences: ThemePreferences,
    private val settings: Settings,
    val translationManager: TranslationManager
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

    private val _chatBgColor      = MutableStateFlow(
        themePreferences.getColor("chatbg", ThemePreferences.DefaultChatBg)
    )
    val chatBgColor: StateFlow<Color> = _chatBgColor.asStateFlow()

    private val _myBubbleColor    = MutableStateFlow(
        themePreferences.getColor("mybubble", ThemePreferences.DefaultMyBubble)
    )
    val myBubbleColor: StateFlow<Color> = _myBubbleColor.asStateFlow()

    private val _theirBubbleColor = MutableStateFlow(
        themePreferences.getColor("theirbubble", ThemePreferences.DefaultTheirBubble)
    )
    val theirBubbleColor: StateFlow<Color> = _theirBubbleColor.asStateFlow()

    private val _chatBgImage = MutableStateFlow<ByteArray?>(
        settings.getStringOrNull("appearance_chatbg_image")?.let { runCatching { Base64.decode(it) }.getOrNull() }
    )
    val chatBgImage: StateFlow<ByteArray?> = _chatBgImage.asStateFlow()

    private val _myBubbleImage = MutableStateFlow<ByteArray?>(
        settings.getStringOrNull("appearance_mybubble_image")?.let { runCatching { Base64.decode(it) }.getOrNull() }
    )
    val myBubbleImage: StateFlow<ByteArray?> = _myBubbleImage.asStateFlow()

    private val _theirBubbleImage = MutableStateFlow<ByteArray?>(
        settings.getStringOrNull("appearance_theirbubble_image")?.let { runCatching { Base64.decode(it) }.getOrNull() }
    )
    val theirBubbleImage: StateFlow<ByteArray?> = _theirBubbleImage.asStateFlow()

    private val _isLoading        = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error            = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Unread count at the moment the chat was opened (from the server). */
    private val _initialUnreadCount = MutableStateFlow(0)
    val initialUnreadCount: StateFlow<Int> = _initialUnreadCount.asStateFlow()

    var currentConversationId: String? = null
        private set
    var peerUserId: String? = null
        private set

    private val _peerAvatarMediaId = MutableStateFlow<String?>(null)
    val peerAvatarMediaId: StateFlow<String?> = _peerAvatarMediaId.asStateFlow()

    val audioRecorder = createAudioRecorder()
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

    /** The message being replied to (Telegram-style reply bar). */
    private val _replyingTo = MutableStateFlow<Message?>(null)
    val replyingTo: StateFlow<Message?> = _replyingTo.asStateFlow()

    /** Map messageServerId → replyToServerId, populated from server data. */
    private val _replyContext = MutableStateFlow<Map<String, String>>(emptyMap())
    val replyContext: StateFlow<Map<String, String>> = _replyContext.asStateFlow()

    // ── Translation & Transcription ──────────────────────────────────────
    private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
    val translations: StateFlow<Map<String, String>> = _translations.asStateFlow()

    private val _transcriptions = MutableStateFlow<Map<String, String>>(emptyMap())
    val transcriptions: StateFlow<Map<String, String>> = _transcriptions.asStateFlow()

    private val _translatingMessages = MutableStateFlow<Set<String>>(emptySet())
    val translatingMessages: StateFlow<Set<String>> = _translatingMessages.asStateFlow()

    private val _transcribingMessages = MutableStateFlow<Set<String>>(emptySet())
    val transcribingMessages: StateFlow<Set<String>> = _transcribingMessages.asStateFlow()

    private fun Message.stableKey(): String = serverId.ifBlank { id.toString() }

    fun translateMessage(message: Message) {
        val key = message.stableKey()
        if (_translations.value.containsKey(key)) return
        if (_translatingMessages.value.contains(key)) return

        viewModelScope.launch {
            _translatingMessages.update { it + key }
            try {
                val result = translationManager.translate(message.text)
                _translations.update { it + (key to result) }
            } catch (e: Exception) {
                _translations.update { it + (key to "⚠️ ${e.message}") }
            } finally {
                _translatingMessages.update { it - key }
            }
        }
    }

    fun transcribeVoiceMessage(message: Message) {
        val key = message.stableKey()
        if (_transcriptions.value.containsKey(key)) return
        if (_transcribingMessages.value.contains(key)) return

        val mediaId = message.mediaId ?: return
        val audioBytes = _mediaCache.value[mediaId] ?: return

        viewModelScope.launch {
            _transcribingMessages.update { it + key }
            try {
                val result = translationManager.transcribeAudio(audioBytes)
                _transcriptions.update { it + (key to result) }
            } catch (e: Exception) {
                _transcriptions.update { it + (key to "⚠️ ${e.message}") }
            } finally {
                _transcribingMessages.update { it - key }
            }
        }
    }

    fun dismissTranslation(message: Message) {
        _translations.update { it - message.stableKey() }
    }

    fun dismissTranscription(message: Message) {
        _transcriptions.update { it - message.stableKey() }
    }

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
                _initialUnreadCount.value = conv.unreadCount ?: 0
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
                    peer?.userId?.let { peerId ->
                        launch {
                            try {
                                val profile = api.getUserById(peerId)
                                _peerAvatarMediaId.value = profile.avatarMediaId
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) { }

            val mlsReady = syncMlsPending(conversationId)
            if (!mlsReady) {
                _error.value = S.current.mlsNotReady
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
                val newReplyCtx = mutableMapOf<String, String>()

                val uiMessages = sortedMessages.map { msg ->
                    val existing = existingLocalMessages.find { it.serverId == msg.id }
                    val isSentByMe = msg.effectiveSenderId == myId
                    newSenders[msg.id] = msg.effectiveSenderId
                    if (!msg.replyToMessageId.isNullOrBlank()) {
                        newReplyCtx[msg.id] = msg.replyToMessageId
                    }

                    val text = when {
                        existing != null && !existing.text.startsWith("🔒") && (existing.text.isNotBlank() || existing.type != "text") -> existing.text
                        else -> try {
                            mlsManager.decrypt(conversationId, msg.mlsCiphertextB64) ?: S.current.encrypted
                        } catch (_: Exception) {
                            if (isSentByMe) S.current.sentFromOtherDevice else S.current.decryptionError
                        }
                    }

                    val parsed = parseMlsPayload(text)

                    Message(
                        id           = existing?.id ?: msg.id.hashCode(),
                        serverId     = msg.id,
                        text         = parsed.content,
                        isOutgoing   = isSentByMe,
                        timestamp    = msg.createdAt * 1000L,
                        status       = MessageStatus.SENT,
                        type         = if (parsed.type != "text") parsed.type else (existing?.type ?: "text"),
                        mediaId      = parsed.mediaId.takeIf { it.isNotBlank() } ?: existing?.mediaId,
                        senderUserId = msg.effectiveSenderId,
                        groupId      = parsed.groupId ?: existing?.groupId
                    )
                }

                _messageSenders.update { it + newSenders }
                _replyContext.update { it + newReplyCtx }
                chatRepository.saveMessages(uiMessages, conversationId)

                if (sortedMessages.isNotEmpty()) {
                    val serverIdSet = sortedMessages.map { it.id }.toSet()
                    val oldestServerTs = sortedMessages.minOf { it.createdAt } * 1000L
                    existingLocalMessages.forEach { local ->
                        val sid = local.serverId
                        if (sid.isNotBlank()
                            && !sid.startsWith("temp_")
                            && !sid.startsWith("system_")
                            && local.timestamp >= oldestServerTs
                            && sid !in serverIdSet
                        ) {
                            chatRepository.deleteMessageByServerId(sid)
                        }
                    }
                }

                mlsManager.flushState()
            }
        } catch (e: Exception) {
            println("MemegramDebug: Ошибка в loadMessages: ${e.message}")
            _error.value = S.current.loadError(e.message ?: "")
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
                if (!data.replyToMessageId.isNullOrBlank()) {
                    _replyContext.update { it + (msgId to data.replyToMessageId) }
                }
                decryptAndSave(
                    convId        = convId,
                    msgId         = msgId,
                    ciphertextB64 = data.mlsCiphertextB64 ?: "",
                    createdAt     = data.createdAt,
                    isOutgoing    = data.senderUserId == myId,
                    senderUserId  = data.senderUserId
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
                chatRepository.deleteMessageByServerId(msgId)
            }

            "epoch_changed" -> syncMlsPending(convId)

            "member_left" -> {
                val userId = data.userId ?: return
                val profile = _memberProfiles.value[userId]
                val name = profile?.username ?: S.current.member
                insertSystemMessage(convId, S.current.leftGroup(name))
            }

            "member_kicked" -> {
                val userId = data.userId ?: return
                val kickedByUserId = data.kickedBy
                val profile = _memberProfiles.value[userId]
                var kickerProfile = kickedByUserId?.let { _memberProfiles.value[it] }
                if (kickerProfile == null && kickedByUserId != null) {
                    kickerProfile = try {
                        val fetched = api.getUserById(kickedByUserId)
                        _memberProfiles.update { it + (kickedByUserId to fetched) }
                        fetched
                    } catch (_: Exception) { null }
                }
                val kickedName = profile?.username ?: S.current.member
                val kickerName = kickerProfile?.username ?: S.current.admin
                insertSystemMessage(convId, S.current.removedFromGroup(kickerName, kickedName))
            }
        }
    }

    // ───────────────────────── System messages ────────────────────────────

    private suspend fun insertSystemMessage(convId: String, text: String) {
        val systemMsg = Message(
            id = text.hashCode() + Clock.System.now().toEpochMilliseconds().toInt(),
            serverId = "system_${Clock.System.now().toEpochMilliseconds()}",
            text = text,
            isOutgoing = false,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            status = MessageStatus.SENT,
            type = "system"
        )
        chatRepository.saveMessage(systemMsg, convId)
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
            if (!_isGroupChat.value && peerUserId != null) {
                try {
                    val profile = api.getUserById(peerUserId!!)
                    if (profile.avatarMediaId != _peerAvatarMediaId.value) {
                        _peerAvatarMediaId.value = profile.avatarMediaId
                    }
                } catch (_: Exception) {}
            }

            val serverMessages = api.getMessages(conversationId)
            val localMessages  = chatRepository.getMessagesOnce(conversationId)
            val localServerIds = localMessages.map { it.serverId }.toSet()

            if (serverMessages.isNotEmpty()) {
                val serverIdSet = serverMessages.map { it.id }.toSet()
                val oldestServerTs = serverMessages.minOf { it.createdAt } * 1000L
                localMessages.forEach { local ->
                    val sid = local.serverId
                    if (sid.isNotBlank()
                        && !sid.startsWith("temp_")
                        && !sid.startsWith("system_")
                        && local.timestamp >= oldestServerTs
                        && sid !in serverIdSet
                    ) {
                        chatRepository.deleteMessageByServerId(sid)
                    }
                }
            }

            val newMessages = serverMessages
                .filter { it.id !in localServerIds }
                .sortedBy { it.createdAt }

            if (newMessages.isEmpty()) return

            println("MemegramDebug [Poll]: найдено ${newMessages.size} новых сообщений")

            val newSenders = mutableMapOf<String, String>()
            val newReplyCtx = mutableMapOf<String, String>()
            for (msg in newMessages) {
                newSenders[msg.id] = msg.effectiveSenderId
                if (!msg.replyToMessageId.isNullOrBlank()) {
                    newReplyCtx[msg.id] = msg.replyToMessageId
                }
                decryptAndSave(
                    convId        = conversationId,
                    msgId         = msg.id,
                    ciphertextB64 = msg.mlsCiphertextB64,
                    createdAt     = msg.createdAt,
                    isOutgoing    = msg.effectiveSenderId == myId,
                    senderUserId  = msg.effectiveSenderId
                )
            }
            _messageSenders.update { it + newSenders }
            if (newReplyCtx.isNotEmpty()) {
                _replyContext.update { it + newReplyCtx }
            }
        } catch (_: Exception) { }
    }

    // ───────────────────────── General logic of decryption ─────────────────────────

    private suspend fun decryptAndSave(
        convId: String,
        msgId: String,
        ciphertextB64: String,
        createdAt: Long,
        isOutgoing: Boolean,
        senderUserId: String? = null
    ) {
        decryptMutex.withLock {
            val alreadyExists = chatRepository.getMessagesOnce(convId)
                .any { it.serverId == msgId && it.text.isNotBlank() && it.text != "🔒" }
            if (alreadyExists) return@withLock

            val plaintext = try {
                mlsManager.decrypt(convId, ciphertextB64) ?: return@withLock
            } catch (_: Exception) { return@withLock }

            mlsManager.flushState()

            val parsed = parseMlsPayload(plaintext)

            val msg = Message(
                id           = msgId.hashCode(),
                serverId     = msgId,
                text         = parsed.content,
                isOutgoing   = isOutgoing,
                timestamp    = createdAt * 1000L,
                status       = MessageStatus.SENT,
                type         = if (parsed.type != "text") parsed.type else "text",
                mediaId      = parsed.mediaId.takeIf { it.isNotBlank() },
                senderUserId = senderUserId,
                groupId      = parsed.groupId
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
                    val groupId = generateUuid()
                    sendPhotoMessageInternal(convId, attachments[0], caption = text, groupId = groupId)
                    attachments.drop(1).forEach { sendPhotoMessageInternal(convId, it, caption = "", groupId = groupId) }
                }
            }
        }
    }

    private suspend fun sendTextMessageInternal(convId: String, text: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val replyTo = _replyingTo.value
        _replyingTo.value = null

        val tempMsg = Message(
            id = now.hashCode(), text = text, isOutgoing = true,
            timestamp = now, status = MessageStatus.SENDING, type = "text",
            senderUserId = myUserId
        )
        chatRepository.saveMessage(tempMsg, convId)

        try {
            if (!mlsManager.hasGroup(convId)) {
                chatRepository.saveMessage(tempMsg.copy(text = S.current.encryptionNotReady, status = MessageStatus.FAILED), convId)
                return
            }
            val ciphertextB64 = mlsManager.encrypt(convId, text)
            mlsManager.flushState()
            val response = api.sendMessage(
                conversationId = convId,
                request = SendMessageRequest(
                    mlsCiphertextB64 = ciphertextB64,
                    type = "text",
                    clientMessageId = generateUuid(),
                    replyToMessageId = replyTo?.serverId
                )
            )
            val sentMsg = tempMsg.copy(serverId = response.messageId, status = MessageStatus.SENT)
            chatRepository.saveMessage(sentMsg, convId)
            if (replyTo != null && replyTo.serverId.isNotBlank()) {
                _replyContext.update { it + (response.messageId to replyTo.serverId) }
            }
        } catch (e: Exception) {
            chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED), convId)
            _error.value = S.current.sendError(e.message ?: "")
        }
    }

    private suspend fun sendPhotoMessageInternal(convId: String, item: AttachItem, caption: String = "", groupId: String? = null) {
        val now = Clock.System.now().toEpochMilliseconds()
        val previewBytes = runCatching { item.readUploadBytes() }.getOrNull()
        val replyTo = _replyingTo.value
        _replyingTo.value = null

        val tempMsg = Message(
            id = now.hashCode(), text = caption, isOutgoing = true,
            timestamp = now, status = MessageStatus.SENDING,
            type = "image", localPreviewBytes = previewBytes,
            senderUserId = myUserId, groupId = groupId
        )
        chatRepository.saveMessage(tempMsg, convId)

        try {
            if (!mlsManager.hasGroup(convId)) {
                chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED, text = S.current.encryptionNotReady), convId)
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

            val mlsPayload    = if (groupId != null) "[image:${initResp.mediaId}:$groupId]$caption"
                                else "[image:${initResp.mediaId}]$caption"
            val ciphertextB64 = mlsManager.encrypt(convId, mlsPayload)
            mlsManager.flushState()

            val response = api.sendMessage(
                conversationId = convId,
                request = SendMessageRequest(
                    mlsCiphertextB64 = ciphertextB64,
                    type             = "image",
                    clientMessageId  = generateUuid(),
                    mediaId          = initResp.mediaId,
                    replyToMessageId = replyTo?.serverId
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
            if (replyTo != null && replyTo.serverId.isNotBlank()) {
                _replyContext.update { it + (response.messageId to replyTo.serverId) }
            }
        } catch (e: Exception) {
            println("MemegramDebug [Photo] ❌ FATAL: ${e::class.simpleName}: ${e.message}")
            chatRepository.saveMessage(
                tempMsg.copy(status = MessageStatus.FAILED, text = S.current.photoSendError), convId
            )
            _error.value = S.current.photoSendErrorDetail(e.message ?: "")
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
            type = "voice",
            senderUserId = myUserId
        )
        viewModelScope.launch {
            chatRepository.saveMessage(tempMsg, convId)

            try {
                if (!mlsManager.hasGroup(convId)) {
                    chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED, text = S.current.encryptionNotReady), convId)
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
                _error.value = S.current.voiceSendError(e.message ?: "")
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

    fun setReplyTo(message: Message?) { _replyingTo.value = message }
    fun clearReply() { _replyingTo.value = null }

    fun deleteMessage(message: Message) {
        val convId = currentConversationId ?: return
        if (message.serverId.isBlank()) return
        viewModelScope.launch {
            try {
                api.deleteMessage(message.serverId, deleteForEveryone = true)
                chatRepository.deleteMessageByServerId(message.serverId)
            } catch (e: Exception) {
                _error.value = S.current.deleteError(e.message ?: "")
            }
        }
    }

    private data class ParsedMlsPayload(
        val type: String,
        val mediaId: String,
        val content: String,
        val groupId: String? = null
    )

    private fun parseMlsPayload(payload: String): ParsedMlsPayload {
        if (payload.startsWith("[image:")) {
            val closeIdx = payload.indexOf(']')
            if (closeIdx == -1) return ParsedMlsPayload("text", "", payload)
            val metaInfo = payload.substring(7, closeIdx).split(":")
            val mediaId = metaInfo[0]
            val groupId = if (metaInfo.size > 1) metaInfo[1] else null
            val caption = payload.substring(closeIdx + 1).trim()
            return ParsedMlsPayload("image", mediaId, caption, groupId)
        }
        if (payload.startsWith("[voice:")) {
            val closeIdx = payload.indexOf(']')
            if (closeIdx == -1) return ParsedMlsPayload("text", "", payload)

            val metaInfo = payload.substring(7, closeIdx).split(":")
            val mediaId = metaInfo[0]
            val waveform = if (metaInfo.size > 1) metaInfo[1] else ""
            val durationMs = payload.substring(closeIdx + 1).trim()

            return ParsedMlsPayload("voice", mediaId, "$durationMs|$waveform")
        }
        return ParsedMlsPayload("text", "", payload)
    }

    override fun onCleared() {
        super.onCleared()
        translationManager.release()
        if (ActiveChatCoordinator.conversationId == currentConversationId) {
            ActiveChatCoordinator.conversationId = null
        }
        sseJob?.cancel()
        pollingJob?.cancel()
        typingJob?.cancel()
        dbObserveJob?.cancel()
    }
}

/**
 * In-memory cache for per-chat scroll positions.
 * Survives navigation between screens but NOT app restart.
 */
object ChatScrollCache {
    private val positions = mutableMapOf<String, Pair<Int, Int>>()

    fun save(conversationId: String, index: Int, offset: Int) {
        positions[conversationId] = index to offset
    }

    fun restore(conversationId: String): Pair<Int, Int>? = positions[conversationId]
}