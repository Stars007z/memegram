package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.audio.AudioRecordResult
import com.example.memegram.audio.createAudioRecorder
import com.example.memegram.auth.SessionRefresher
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
import com.example.memegram.data.files.openSavedFile
import com.example.memegram.data.files.saveDownloadedFile
import com.example.memegram.mls.MlsCommitProcessResult
import com.example.memegram.mls.MlsDecryptResult
import com.example.memegram.mls.decryptMediaBytes
import com.example.memegram.mls.encryptMediaBytes
import com.example.memegram.localization.S
import com.example.memegram.nsfw.NsfwService
import com.example.memegram.nsfw.NsfwSettings
import com.example.memegram.translation.TranslationService
import com.example.memegram.translation.TranslationProgress
import com.example.memegram.translation.TranslationSettings
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
    private val translationService: TranslationService,
    private val translationSettings: TranslationSettings,
    private val transcriptionService: com.example.memegram.transcription.TranscriptionService,
    private val nsfwSettings: NsfwSettings,
    private val nsfwService: NsfwService,
    private val blockedUsersCache: BlockedUsersCache,
    private val profileRepository: com.example.memegram.data.repository.ProfileRepository,
    private val appearance: AppearanceRepository,
    private val sessionRefresher: SessionRefresher,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isGroupChat = MutableStateFlow(false)
    val isGroupChat: StateFlow<Boolean> = _isGroupChat.asStateFlow()

    private val _myRole = MutableStateFlow<String?>(null)
    val myRole: StateFlow<String?> = _myRole.asStateFlow()

    private val _messageSenders = MutableStateFlow<Map<String, String>>(emptyMap())
    val messageSenders: StateFlow<Map<String, String>> = _messageSenders.asStateFlow()

    private val _memberProfiles = MutableStateFlow<Map<String, UserProfileResponse>>(emptyMap())
    val memberProfiles: StateFlow<Map<String, UserProfileResponse>> = _memberProfiles.asStateFlow()

    private val _inputText        = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    val chatBgColor: StateFlow<Color> = appearance.chatBgColor
    val myBubbleColor: StateFlow<Color> = appearance.myBubbleColor
    val theirBubbleColor: StateFlow<Color> = appearance.theirBubbleColor
    val chatBgImage: StateFlow<ByteArray?> = appearance.chatBgImage
    val myBubbleImage: StateFlow<ByteArray?> = appearance.myBubbleImage
    val theirBubbleImage: StateFlow<ByteArray?> = appearance.theirBubbleImage
    val transparentBubbles: StateFlow<Boolean> = appearance.transparentBubbles
    val bubbleTransparency: StateFlow<Float> = appearance.bubbleTransparency
    val myBubbleTextColor: StateFlow<Color?> = appearance.myBubbleTextColor
    val theirBubbleTextColor: StateFlow<Color?> = appearance.theirBubbleTextColor
    val topBarTextColorOverride: StateFlow<Color?> = appearance.topBarTextColor
    val chatBgTextColor: StateFlow<Color?> = appearance.chatBgTextColor
    val appearanceGeneration: StateFlow<Long> = appearance.imageGeneration

    private val _isLoading        = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _muteUntil = MutableStateFlow(0L)
    val muteUntil: StateFlow<Long> = _muteUntil.asStateFlow()

    private val _error            = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    private val _forceClose = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val forceClose: SharedFlow<Unit> = _forceClose.asSharedFlow()

    private suspend fun handlePossibleNotMemberError(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        if (!msg.contains("Not a member of this conversation", ignoreCase = true)) {
            return false
        }
        val convId = currentConversationId ?: return false
        println("MemegramDebug [ChatVM]: server says not-a-member for conv=$convId, purging locally and closing chat")
        runCatching {
            com.example.memegram.conversation.ConversationLocalCleaner.purge(
                convId, chatRepository, mlsManager,
            )
        }
        _forceClose.tryEmit(Unit)
        return true
    }

    private val _initialUnreadCount = MutableStateFlow(0)
    val initialUnreadCount: StateFlow<Int> = _initialUnreadCount.asStateFlow()

    var currentConversationId: String? = null
        private set
    private val _peerUserId = MutableStateFlow<String?>(null)
    var peerUserId: String?
        get() = _peerUserId.value
        private set(value) { _peerUserId.value = value }

    private val _peerAvatarMediaId = MutableStateFlow<String?>(null)
    val peerAvatarMediaId: StateFlow<String?> = _peerAvatarMediaId.asStateFlow()

    private val _isPeerDeleted = MutableStateFlow(false)
    val isPeerDeleted: StateFlow<Boolean> = _isPeerDeleted.asStateFlow()

    private val _peerDisplayName = MutableStateFlow<String?>(null)
    val peerDisplayName: StateFlow<String?> = _peerDisplayName.asStateFlow()

    fun setInitialPeerAvatar(mediaId: String) {
        if (_isPeerDeleted.value) return
        if (_peerAvatarMediaId.value == null) {
            _peerAvatarMediaId.value = mediaId
        }
    }

    val isPeerBlocked: StateFlow<Boolean> = combine(
        _peerUserId, blockedUsersCache.blockedIds
    ) { peerId, ids -> peerId != null && peerId in ids }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isBlockedByPeer = MutableStateFlow(false)
    val isBlockedByPeer: StateFlow<Boolean> = _isBlockedByPeer.asStateFlow()

    private val _isMlsBroken = MutableStateFlow(false)
    val isMlsBroken: StateFlow<Boolean> = _isMlsBroken.asStateFlow()

    private fun handleBlockedByPeerOnSendError(e: Throwable): Boolean {
        val api = e as? com.example.memegram.data.network.ApiException ?: return false
        if (!api.isBlocked) return false
        if (_isGroupChat.value) return false
        _isBlockedByPeer.value = true
        return true
    }

    private suspend fun handleRecipientUnavailableOnSendError(e: Throwable): Boolean {
        val api = e as? com.example.memegram.data.network.ApiException ?: return false
        if (!api.isRecipientUnavailable || _isGroupChat.value) return false
        markPeerDeletedLocally()
        return true
    }

    private suspend fun markPeerDeletedLocally() {
        _isPeerDeleted.value = true
        _peerAvatarMediaId.value = null
        val convId = currentConversationId
        val peerId = peerUserId
        if (convId != null) DeletedPeerStore.markConversationDeleted(settings, convId, peerId)
        if (peerId == null) return
        profileRepository.upsert(
            UserProfileResponse(
                id = peerId,
                username = _peerDisplayName.value,
                isDeleted = true,
            )
        )
    }

    val audioRecorder = createAudioRecorder()
    private var myUserId: String? = null
    private var myDeviceId: String? = null
    private var typingJob: Job? = null
    private var sseJob: Job? = null
    private var dbObserveJob: Job? = null

    private val decryptMutex = Mutex()

    private val _downloadingFiles = MutableStateFlow<Set<String>>(emptySet())
    val downloadingFiles: StateFlow<Set<String>> = _downloadingFiles.asStateFlow()

    private val _translationProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val translationProgress: StateFlow<Map<String, Float>> = _translationProgress.asStateFlow()

    val transcriptionProgress: StateFlow<Map<String, Float>> = TranscriptionProgressTracker.progress

    private val _visibleTranscriptions = MutableStateFlow<Set<String>>(emptySet())
    val visibleTranscriptions: StateFlow<Set<String>> = _visibleTranscriptions.asStateFlow()

    private fun updateTranslationProgress(messageId: String, progress: TranslationProgress) {
        _translationProgress.update { current ->
            if (messageId !in current) current
            else current + (messageId to progress.fraction.coerceIn(0f, 0.99f))
        }
    }

    private suspend fun finishTranslationProgress(messageId: String) {
        var wasVisible = false
        _translationProgress.update { current ->
            wasVisible = messageId in current
            if (wasVisible) current + (messageId to 1f) else current
        }
        if (wasVisible) delay(220)
    }

    companion object {
        const val MAX_UPLOAD_SIZE_BYTES: Long = 100L * 1024L * 1024L
        const val AUTO_DOWNLOAD_FILE_LIMIT_BYTES: Long = 5L * 1024L * 1024L
        const val INLINE_BLOB_LIMIT_BYTES: Int = 2 * 1024 * 1024
    }

    private fun serverTimestampMs(createdAtSeconds: Long, fallback: Long): Long =
        if (createdAtSeconds > 0L) createdAtSeconds * 1000L else fallback

    enum class RecordState { IDLE, HOLDING, LOCKED, PAUSED }
    private val _recordState = MutableStateFlow(RecordState.IDLE)
    val recordState: StateFlow<RecordState> = _recordState.asStateFlow()

    private val _voiceAmplitudes = MutableStateFlow<List<Int>>(emptyList())
    val voiceAmplitudes: StateFlow<List<Int>> = _voiceAmplitudes.asStateFlow()

    private val _voiceDurationMs = MutableStateFlow(0L)
    val voiceDurationMs: StateFlow<Long> = _voiceDurationMs.asStateFlow()

    private val _replyingTo = MutableStateFlow<Message?>(null)
    val replyingTo: StateFlow<Message?> = _replyingTo.asStateFlow()

    private val _replyContext = MutableStateFlow<Map<String, String>>(emptyMap())
    val replyContext: StateFlow<Map<String, String>> = _replyContext.asStateFlow()

    private var recordTimerJob: Job? = null
    private var rawAmplitudes = mutableListOf<Int>()

    fun loadConversation(conversationId: String) {
        if (currentConversationId == conversationId) return
        currentConversationId = conversationId
        _myRole.value = null
        myUserId = sessionManager.getUserId()
        myDeviceId = sessionManager.getDeviceId()
        ActiveChatCoordinator.conversationId = conversationId
        if (DeletedPeerStore.isConversationDeleted(settings, conversationId)) {
            _isPeerDeleted.value = true
            _peerAvatarMediaId.value = null
        }
        DeletedPeerStore.conversationPeerId(settings, conversationId)
            ?.let { peerUserId = it }

        sseJob?.cancel()
        dbObserveJob?.cancel()

        dbObserveJob = viewModelScope.launch {
            combine(
                chatRepository.getMessagesFlow(conversationId),
                blockedUsersCache.blockedIds,
                nsfwSettings.filterRevision,
            ) { msgs, blockedIds, revision ->
                revision to msgs.asSequence()
                    .filterNot { m ->
                        val sender = m.senderUserId
                        sender != null && sender != myUserId && sender in blockedIds
                    }
                    .sortedBy { it.timestamp }
                    .toList()
            }.collect { (_, msgs) ->
                val warm = LinkedHashMap<String, ByteArray>()
                val current = _mediaCache.value
                val localReplyCtx = mutableMapOf<String, String>()
                val displayMessages = mutableListOf<Message>()
                val filterEnabled = nsfwSettings.filterEnabled.value
                val nsfwModelAvailable = nsfwService.isModelAvailable()
                msgs.forEach { m ->
                    val mid = m.mediaId
                    val bytes = m.localPreviewBytes
                    val imageMediaId = mid?.takeIf { !m.isOutgoing && m.type == "image" }
                    val refreshIncomingImage = imageMediaId != null &&
                        ((filterEnabled && nsfwModelAvailable && !nsfwSettings.hasProcessedMedia(imageMediaId)) ||
                            (!filterEnabled && nsfwSettings.hasProcessedMedia(imageMediaId)))
                    if (mid != null && bytes != null && mid !in current && mid !in warm && !refreshIncomingImage) {
                        warm[mid] = bytes
                    } else if (imageMediaId != null && refreshIncomingImage) {
                        loadMedia(
                            mediaId = imageMediaId,
                            encryptionMetadata = m.encryptionMetadata,
                            forceReload = true,
                            knownMessage = m,
                            mlPriority = com.example.memegram.ml.MlModelGate.Priority.AUTO,
                        )
                    }
                    displayMessages += if (refreshIncomingImage) m.copy(localPreviewBytes = null) else m
                    val sid = m.serverId
                    val replySid = m.replyToServerId
                    if (sid.isNotBlank() && !replySid.isNullOrBlank()) {
                        localReplyCtx[sid] = replySid
                    }
                }
                if (warm.isNotEmpty()) _mediaCache.value = current + warm
                if (localReplyCtx.isNotEmpty()) _replyContext.update { it + localReplyCtx }
                _messages.value = displayMessages
            }
        }

        subscribeToEvents(conversationId)

        viewModelScope.launch {
            val cachedChat = runCatching { chatRepository.getChatById(conversationId) }.getOrNull()
            cachedChat?.let { _muteUntil.value = it.muteUntil }
            cachedChat?.peerUserId?.takeIf { it.isNotBlank() && peerUserId == null }
                ?.let { peerUserId = it }

            val convInfoJob = launch {
                try {
                    val conv = api.getConversation(conversationId)
                    _initialUnreadCount.value = conv.unreadCount ?: 0
                    val isGroup = conv.type != "direct"
                    _isGroupChat.value = isGroup
                    _isBlockedByPeer.value = !isGroup && conv.isBlockedByPeer
                    _myRole.value = conv.members.find { it.userId == myUserId }?.role

                    if (isGroup) {
                        _peerAvatarMediaId.value = conv.avatarMediaId?.takeIf { it.isNotBlank() }

                        conv.members.forEach { member ->
                            if (member.userId != myUserId && !_memberProfiles.value.containsKey(member.userId)) {
                                launch {
                                    try {
                                        val profile = profileRepository.getOrFetch(member.userId) ?: return@launch
                                        _memberProfiles.update { it + (member.userId to profile) }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } else {
                        val peer = conv.members.find { it.userId != myUserId }
                        val existingPeerId = peerUserId
                        val resolvedPeerId = peer?.userId ?: existingPeerId
                        peerUserId = resolvedPeerId
                        if (DeletedPeerStore.isConversationDeleted(settings, conversationId)) {
                            _isPeerDeleted.value = true
                            _peerAvatarMediaId.value = null
                            resolvedPeerId?.let { DeletedPeerStore.markConversationDeleted(settings, conversationId, it) }
                        }
                        resolvedPeerId?.let { peerId ->
                            if (_myRole.value == "owner" || _myRole.value == "admin") {
                                launch {
                                    runCatching { api.updateMemberRole(conversationId, peerId, "admin") }
                                        .onFailure { e ->
                                            println("MemegramDebug [DirectRole]: peer admin grant skipped: ${e.message}")
                                        }
                                }
                            }
                            launch {
                                try {
                                    val profile = profileRepository.getOrFetch(peerId) ?: return@launch
                                    val deleted = profile.isDeleted || DeletedPeerStore.isDeleted(settings, conversationId, peerId)
                                    _peerAvatarMediaId.value = if (deleted) null else profile.avatarMediaId
                                    _isPeerDeleted.value = deleted
                                    _peerDisplayName.value = profile.username
                                    if (deleted) DeletedPeerStore.markConversationDeleted(settings, conversationId, peerId)
                                } catch (_: Exception) {}
                            }
                        }
                        conv.peerLastReadMessageId?.takeIf { it.isNotBlank() }?.let { lastReadId ->
                            launch {
                                runCatching {
                                    chatRepository.markOutgoingMessagesRead(conversationId, lastReadId)
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            val mlsReady = syncMlsPending(conversationId)
            if (!mlsReady) {
                return@launch
            }

            loadMessages(conversationId)
        }
    }

    private suspend fun loadMessages(conversationId: String) {
        _isLoading.value = true
        try {
            val rawMessages = api.getMessages(conversationId)
            val myId = myUserId ?: ""
            val lastBlockedFromServer = rawMessages
                .filter { it.effectiveSenderId != myId && blockedUsersCache.isBlocked(it.effectiveSenderId) }
                .maxByOrNull { it.createdAt }
            if (lastBlockedFromServer != null) {
                viewModelScope.launch {
                    runCatching { api.markAsRead(conversationId, MarkAsReadRequest(lastBlockedFromServer.id)) }
                }
            }
            val sortedMessages = rawMessages.sortedBy { it.createdAt }
            decryptMutex.withLock {
                val existingLocalMessages = chatRepository.getMessagesOnce(conversationId)
                val newSenders = mutableMapOf<String, String>()
                val newReplyCtx = mutableMapOf<String, String>()

                val uiMessages = sortedMessages.mapNotNull { msg ->
                    val existing = existingLocalMessages.find { it.serverId == msg.id }
                    val isSentByMe = msg.effectiveSenderId == myId

                    val text: String = run {
                        val cached = existing?.text
                        val cachedUsable = existing != null
                                && cached != null
                                && !cached.startsWith("🔒")
                                && (cached.isNotBlank() || existing.type != "text")
                        if (cachedUsable) return@run cached!!

                        when (val decrypted = mlsManager.decryptResult(conversationId, msg.mlsCiphertextB64)) {
                            is MlsDecryptResult.Success -> decrypted.plaintext
                            is MlsDecryptResult.Failure -> when {
                                isSentByMe -> S.current.sentFromOtherDevice
                                decrypted.permanent -> ""
                                else -> return@mapNotNull null
                            }
                        }
                    }

                    newSenders[msg.id] = msg.effectiveSenderId
                    if (!msg.replyToMessageId.isNullOrBlank()) {
                        newReplyCtx[msg.id] = msg.replyToMessageId
                    }

                    val parsed = parseMlsPayload(text)
                    val serverMediaType = msg.type?.takeIf { it == "image" || it == "voice" || it == "file" }
                    val restoredMediaType = existing
                        ?.takeIf { it.mediaId != null && it.type != "text" && it.type != "undecryptable" }
                        ?.type
                    val messageType = when {
                        parsed.type != "text" -> parsed.type
                        serverMediaType != null -> serverMediaType
                        restoredMediaType != null -> restoredMediaType
                        text.isBlank() -> "undecryptable"
                        else -> existing?.type ?: "text"
                    }

                    Message(
                        id           = existing?.id ?: msg.id.hashCode(),
                        serverId     = msg.id,
                        text         = parsed.content,
                        isOutgoing   = isSentByMe,
                        timestamp    = msg.createdAt * 1000L,
                        status       = if (isSentByMe && existing?.status == MessageStatus.READ) MessageStatus.READ else MessageStatus.SENT,
                        type         = messageType,
                        mediaId      = parsed.mediaId.takeIf { it.isNotBlank() }
                            ?: msg.mediaId?.takeIf { it.isNotBlank() }
                            ?: existing?.mediaId,
                        encryptionMetadata = existing?.encryptionMetadata,
                        senderUserId = msg.effectiveSenderId,
                        groupId      = parsed.groupId ?: existing?.groupId,
                        fileName     = parsed.fileName ?: existing?.fileName,
                        fileSize     = parsed.fileSize ?: existing?.fileSize,
                        fileMime     = parsed.fileMime ?: existing?.fileMime,
                        localFilePath = existing?.localFilePath,
                        localPreviewBytes = existing?.localPreviewBytes,
                        replyToServerId = msg.replyToMessageId?.takeIf { it.isNotBlank() }
                            ?: existing?.replyToServerId?.takeIf { it.isNotBlank() }
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
            if (!handlePossibleNotMemberError(e)) {
                _error.value = S.current.loadError(e.message ?: "")
            }
        } finally {
            _isLoading.value = false
        }
    }

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
                val senderId = data.senderUserId
                val isFromBlocked = senderId != null && senderId != myId && blockedUsersCache.isBlocked(senderId)
                if (isFromBlocked) {
                    viewModelScope.launch {
                        runCatching { api.markAsRead(convId, MarkAsReadRequest(msgId)) }
                    }
                }
                senderId?.let { sId ->
                    _messageSenders.update { it + (msgId to sId) }
                }
                if (!data.replyToMessageId.isNullOrBlank()) {
                    _replyContext.update { it + (msgId to data.replyToMessageId) }
                }
                decryptAndSave(
                    convId        = convId,
                    msgId         = msgId,
                    ciphertextB64 = data.mlsCiphertextB64 ?: "",
                    createdAt     = data.createdAt,
                    isOutgoing    = senderId == myId,
                    senderUserId  = senderId,
                    replyToServerId = data.replyToMessageId?.takeIf { it.isNotBlank() }
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

            "message_read" -> {
                val readerId = data.userId ?: return
                val lastReadId = data.lastReadMessageId ?: return
                if (readerId == myId) return
                chatRepository.markOutgoingMessagesRead(convId, lastReadId)
            }

            "epoch_changed" -> syncMlsPending(convId)

            "device_revoked" -> {
                val revokedDeviceId = data.revokedDeviceId ?: return
                if (revokedDeviceId == myDeviceId) {
                    sessionRefresher.markRevoked("Доступ этого устройства отозван")
                    sseJob?.cancel()
                    dbObserveJob?.cancel()
                    return
                }
                syncMlsPending(convId)
            }

            "member_left" -> {
                val userId = data.userId ?: return
                val reason = data.reason
                val isDirect = !_isGroupChat.value

                if (isDirect && (userId == peerUserId || peerUserId == null)) {
                    if (peerUserId == null) peerUserId = userId
                    viewModelScope.launch {
                        try {
                            DeletedPeerStore.markConversationDeleted(settings, convId, userId)
                            profileRepository.upsert(
                                UserProfileResponse(
                                    id = userId,
                                    username = _peerDisplayName.value,
                                    isDeleted = true,
                                )
                            )
                            val refreshed = profileRepository.refresh(userId)
                                ?: profileRepository.getOrFetch(userId, forceRefresh = true)
                            if (refreshed != null) {
                                _isPeerDeleted.value = true
                                _peerDisplayName.value = refreshed.username
                                _peerAvatarMediaId.value = null
                            } else {
                                _isPeerDeleted.value = true
                            }
                        } catch (_: Exception) {
                            _isPeerDeleted.value = true
                        }
                    }
                    return
                }

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
                        val fetched = profileRepository.getOrFetch(kickedByUserId)
                        if (fetched != null) {
                            _memberProfiles.update { it + (kickedByUserId to fetched) }
                        }
                        fetched
                    } catch (_: Exception) { null }
                }
                val kickedName = profile?.username ?: S.current.member
                val kickerName = kickerProfile?.username ?: S.current.admin
                insertSystemMessage(convId, S.current.removedFromGroup(kickerName, kickedName))
            }
        }
    }

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

    private suspend fun decryptAndSave(
        convId: String,
        msgId: String,
        ciphertextB64: String,
        createdAt: Long,
        isOutgoing: Boolean,
        senderUserId: String? = null,
        replyToServerId: String? = null
    ) {
        decryptMutex.withLock {
            val alreadyExists = chatRepository.getMessagesOnce(convId)
                .any { it.serverId == msgId && it.text.isNotBlank() && it.text != "🔒" }
            if (alreadyExists) return@withLock

            val plaintext = when (val decrypted = mlsManager.decryptResult(convId, ciphertextB64)) {
                is MlsDecryptResult.Success -> decrypted.plaintext
                is MlsDecryptResult.Failure -> {
                    if (decrypted.permanent) {
                        saveUndecryptablePlaceholder(
                            convId = convId,
                            msgId = msgId,
                            createdAt = createdAt,
                            isOutgoing = isOutgoing,
                            senderUserId = senderUserId,
                            replyToServerId = replyToServerId,
                        )
                    }
                    return@withLock
                }
            }

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
                groupId      = parsed.groupId,
                fileName     = parsed.fileName,
                fileSize     = parsed.fileSize,
                fileMime     = parsed.fileMime,
                replyToServerId = replyToServerId?.takeIf { it.isNotBlank() }
            )
            chatRepository.saveMessage(msg, convId)
            if (!isOutgoing && parsed.type == "file" && parsed.mediaId.isNotBlank()
                && (parsed.fileSize ?: 0L) in 1L..AUTO_DOWNLOAD_FILE_LIMIT_BYTES) {
                viewModelScope.launch { downloadFile(msg) }
            }
            val MAX_AUTO_TRANSLATE_LENGTH = 300
            if (!isOutgoing && parsed.type == "text" && parsed.content.isNotBlank()
                && parsed.content.length <= MAX_AUTO_TRANSLATE_LENGTH) {
                val appLang = settings.getString("app_language", "en")
                if (translationSettings.autoTranslateEnabled.value) {
                    viewModelScope.launch {
                        var added = false
                        _translationProgress.update { current ->
                            if (msgId in current) current
                            else {
                                added = true
                                current + (msgId to 0f)
                            }
                        }
                        if (!added) {
                            println("MemegramDebug [AutoTranslate]: skip, already translating $msgId")
                            return@launch
                        }
                        try {
                            val detected = translationService.identifyLanguage(parsed.content)
                            val targetLang = translationSettings.getEffectiveTargetLang(appLang)
                            println("MemegramDebug [AutoTranslate]: detected=$detected appLang=$appLang targetLang=$targetLang text='${parsed.content.take(40)}'")

                            val shouldTranslate = when {
                                detected == null -> true
                                else -> translationSettings.shouldAutoTranslate(detected, appLang)
                            }

                            if (shouldTranslate) {
                                val result = com.example.memegram.ml.MlModelGate.withModel(
                                    com.example.memegram.ml.MlModelGate.Priority.AUTO
                                ) {
                                    translationService.translate(
                                        text = parsed.content,
                                        sourceLang = detected,
                                        targetLang = targetLang,
                                        onProgress = { progress -> updateTranslationProgress(msgId, progress) }
                                    )
                                }
                                println("MemegramDebug [AutoTranslate]: result='${result.translatedText.take(40)}' srcLang=${result.detectedSourceLang}")
                                if (result.translatedText != parsed.content) {
                                    chatRepository.updateMessageTranslation(
                                        msgId, result.translatedText, result.detectedSourceLang
                                    )
                                }
                            } else {
                                _translationProgress.update { it + (msgId to 1f) }
                            }
                        } catch (e: Exception) {
                            println("MemegramDebug [AutoTranslate]: Error translating msgId=$msgId: ${e.message}")
                        } finally {
                            finishTranslationProgress(msgId)
                            _translationProgress.update { it - msgId }
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveUndecryptablePlaceholder(
        convId: String,
        msgId: String,
        createdAt: Long,
        isOutgoing: Boolean,
        senderUserId: String?,
        replyToServerId: String?,
    ) {
        println("MemegramDebug [MLS]: skip permanently undecryptable message msg=$msgId conv=$convId")
        chatRepository.saveMessage(
            Message(
                id = msgId.hashCode(),
                serverId = msgId,
                text = "",
                isOutgoing = isOutgoing,
                timestamp = createdAt * 1000L,
                status = MessageStatus.SENT,
                type = "undecryptable",
                senderUserId = senderUserId,
                replyToServerId = replyToServerId?.takeIf { it.isNotBlank() }
            ),
            convId
        )
    }

    private suspend fun findWelcomeWithRetry(
        conversationId: String,
        maxAttempts: Int = 1,
        delayMs: Long = 4_000L
    ): WelcomeResponse? {
        repeat(maxAttempts) { attempt ->
            val welcomes = try { api.getPendingWelcomes() } catch (_: Exception) { emptyList() }
            val match = welcomes.find { it.conversationId == conversationId }
            if (match != null) return match
            if (attempt < maxAttempts - 1) {
                println("MemegramDebug [MLS]: no Welcome for conv=$conversationId (attempt ${attempt + 1}/$maxAttempts), retry in ${delayMs}ms")
                delay(delayMs)
            }
        }
        return null
    }

    private fun retryWelcomeInBackground(conversationId: String) {
        viewModelScope.launch {
            val delays = longArrayOf(3_000L, 6_000L, 12_000L)
            for ((idx, d) in delays.withIndex()) {
                delay(d)
                if (currentConversationId != conversationId) return@launch
                if (mlsManager.hasGroup(conversationId)) return@launch
                println("MemegramDebug [MLS]: background welcome retry ${idx + 1}/${delays.size} for conv=$conversationId")
                val ready = syncMlsPending(conversationId, allowBackgroundRetry = false)
                if (ready && mlsManager.hasGroup(conversationId)) {
                    loadMessages(conversationId)
                    return@launch
                }
            }
            if (!mlsManager.hasGroup(conversationId) && currentConversationId == conversationId) {
                println("MemegramDebug [MLS]: background retries exhausted, mark mls_broken conv=$conversationId")
                mlsManager.markChatMlsBroken(conversationId)
                _isMlsBroken.value = true
            }
        }
    }

    private suspend fun syncMlsPending(
        conversationId: String,
        allowBackgroundRetry: Boolean = true,
    ): Boolean {
        if (mlsManager.isChatMlsBroken(conversationId)) {
            ackBrokenConversationWelcome(conversationId)
            _isMlsBroken.value = true
            return false
        }

        var justProcessedWelcome = false

        if (!mlsManager.hasGroup(conversationId)) {
            val welcome = try {
                findWelcomeWithRetry(conversationId)
            } catch (_: Exception) { null }

            if (welcome != null) {
                try {
                    mlsManager.processWelcome(conversationId, welcome.welcomeDataB64)
                    api.ackWelcome(welcome.id)
                    justProcessedWelcome = true
                } catch (_: Exception) {
                    if (mlsManager.isChatMlsBroken(conversationId)) {
                        println("MemegramDebug [MLS]: stale Welcome detected, ack and stop retry conv=$conversationId")
                        runCatching { api.ackWelcome(welcome.id) }
                            .onFailure { println("MemegramDebug [MLS]: stale Welcome ack failed: ${it.message}") }
                        _isMlsBroken.value = true
                    }
                    return false
                }
            } else {
                if (allowBackgroundRetry) {
                    println("MemegramDebug [MLS]: No Welcome for conv=$conversationId on first try, scheduling background retries")
                    retryWelcomeInBackground(conversationId)
                }
                return false
            }
        }

        try {
            if (justProcessedWelcome) {
                val realEpoch = mlsManager.getRealMlsEpoch(conversationId)
                mlsManager.updateGroupEpoch(conversationId, realEpoch)
                println("MemegramDebug [Welcome]: Синхронизирована metadata-эпоха с реальной MLS = $realEpoch")
                return true
            }

            val cursorEpoch = mlsManager.getCommitCursor(conversationId)
                ?: mlsManager.getGroupEpoch(conversationId)
            val commits = api.getPendingCommits(conversationId, cursorEpoch)
            val newCommits = commits.filter { it.epoch > cursorEpoch }

            if (newCommits.isNotEmpty()) {
                newCommits.sortedBy { it.epoch }.forEach { commit ->
                    val result = try {
                        mlsManager.processCommitResult(conversationId, commit.commitDataB64)
                    } catch (e: Exception) {
                        MlsCommitProcessResult.Skipped(permanent = false, message = e.message)
                    }

                    when (result) {
                        is MlsCommitProcessResult.Applied -> {
                            val realEpoch = mlsManager.getRealMlsEpoch(conversationId)
                            mlsManager.updateGroupEpoch(conversationId, realEpoch)
                            mlsManager.updateCommitCursor(conversationId, commit.epoch)
                        }
                        is MlsCommitProcessResult.Skipped -> {
                            if (result.permanent) mlsManager.updateCommitCursor(conversationId, commit.epoch)
                        }
                    }
                }
                mlsManager.flushState()
            }
        } catch (_: Exception) { return false }

        return true
    }

    private suspend fun ackBrokenConversationWelcome(conversationId: String) {
        val welcome = runCatching { api.getPendingWelcomes().find { it.conversationId == conversationId } }
            .getOrNull()
            ?: return
        println("MemegramDebug [MLS]: ack pending Welcome for MLS-broken conv=$conversationId")
        runCatching { api.ackWelcome(welcome.id) }
            .onFailure { println("MemegramDebug [MLS]: broken Welcome ack failed: ${it.message}") }
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

    fun sendMessage(attachments: List<AttachItem> = emptyList()) {
        val convId = currentConversationId ?: return
        val text = _inputText.value.trim()
        if (text.isBlank() && attachments.isEmpty()) return
        if (isPeerBlocked.value) {
            _error.value = S.current.userBlockedSendError
            return
        }
        if (!_isGroupChat.value && _isPeerDeleted.value) {
            _error.value = S.current.userDeletedAccountBanner
            return
        }
        _inputText.value = ""

        viewModelScope.launch {
            when {
                attachments.isEmpty() -> sendTextMessageInternal(convId, text)
                attachments.size == 1 -> {
                    val a = attachments[0]
                    if (a.asFile) sendFileMessageInternal(convId, a, caption = text)
                    else sendPhotoMessageInternal(convId, a, caption = text)
                }
                else -> {
                    val groupId = generateUuid()
                    attachments.forEachIndexed { idx, a ->
                        val cap = if (idx == 0) text else ""
                        if (a.asFile) sendFileMessageInternal(convId, a, caption = cap, groupId = groupId)
                        else sendPhotoMessageInternal(convId, a, caption = cap, groupId = groupId)
                    }
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
            senderUserId = myUserId,
            replyToServerId = replyTo?.serverId
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
            val sentMsg = tempMsg.copy(
                serverId  = response.messageId,
                status    = MessageStatus.SENT,
                timestamp = serverTimestampMs(response.createdAt, fallback = tempMsg.timestamp),
                replyToServerId = replyTo?.serverId
            )
            chatRepository.saveMessage(sentMsg, convId)
            if (replyTo != null && replyTo.serverId.isNotBlank()) {
                _replyContext.update { it + (response.messageId to replyTo.serverId) }
            }
        } catch (e: Exception) {
            chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED), convId)
            when {
                handlePossibleNotMemberError(e) -> {}
                handleRecipientUnavailableOnSendError(e) -> _error.value = S.current.userDeletedAccountBanner
                handleBlockedByPeerOnSendError(e) -> _error.value = S.current.cannotMessageBlockedByPeer
                else -> _error.value = S.current.sendError(e.message ?: "")
            }
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
            senderUserId = myUserId, groupId = groupId,
            replyToServerId = replyTo?.serverId
        )
        chatRepository.saveMessage(tempMsg, convId)

        try {
            if (!mlsManager.hasGroup(convId)) {
                chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED, text = S.current.encryptionNotReady), convId)
                return
            }

            val rawBytes = previewBytes ?: item.readUploadBytes()
            if (rawBytes.size.toLong() > MAX_UPLOAD_SIZE_BYTES) {
                chatRepository.saveMessage(
                    tempMsg.copy(status = MessageStatus.FAILED, text = S.current.photoSendError), convId
                )
                _error.value = S.current.fileTooLarge(
                    formatSizeBytes(rawBytes.size.toLong()),
                    formatSizeBytes(MAX_UPLOAD_SIZE_BYTES)
                )
                return
            }

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
                    timestamp          = serverTimestampMs(response.createdAt, fallback = tempMsg.timestamp),
                    text               = caption,
                    mediaId            = initResp.mediaId,
                    encryptionMetadata = encrypted.encryptionMetadataB64,
                    replyToServerId    = replyTo?.serverId
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
            when {
                handlePossibleNotMemberError(e) -> {}
                handleRecipientUnavailableOnSendError(e) -> _error.value = S.current.userDeletedAccountBanner
                handleBlockedByPeerOnSendError(e) -> _error.value = S.current.cannotMessageBlockedByPeer
                else -> _error.value = S.current.photoSendErrorDetail(e.message ?: "")
            }
        }
    }

    private suspend fun sendFileMessageInternal(
        convId: String,
        item: AttachItem,
        caption: String = "",
        groupId: String? = null
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val replyTo = _replyingTo.value
        _replyingTo.value = null

        val rawBytes = runCatching { item.readUploadBytes() }.getOrNull()
        if (rawBytes == null) {
            _error.value = S.current.fileSendError(S.current.fileReadError)
            return
        }

        if (rawBytes.size.toLong() > MAX_UPLOAD_SIZE_BYTES) {
            _error.value = S.current.fileTooLarge(
                formatSizeBytes(rawBytes.size.toLong()),
                formatSizeBytes(MAX_UPLOAD_SIZE_BYTES)
            )
            return
        }

        val mime     = item.guessMimeType()
        val fileName = item.name
        val fileSize = rawBytes.size.toLong()

        val tempMsg = Message(
            id = now.hashCode(), text = caption, isOutgoing = true,
            timestamp = now, status = MessageStatus.SENDING,
            type = "file", localPreviewBytes = null,
            senderUserId = myUserId, groupId = groupId,
            fileName = fileName, fileSize = fileSize, fileMime = mime,
            replyToServerId = replyTo?.serverId
        )
        chatRepository.saveMessage(tempMsg, convId)

        try {
            if (!mlsManager.hasGroup(convId)) {
                chatRepository.saveMessage(
                    tempMsg.copy(status = MessageStatus.FAILED, text = S.current.encryptionNotReady),
                    convId
                )
                return
            }

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

            val nameB64 = encodeBase64Utf8(fileName)
            val mimeB64 = encodeBase64Utf8(mime)
            val capB64  = encodeBase64Utf8(caption)
            val mlsPayload = "[file:${initResp.mediaId}:$fileSize:$mimeB64]$nameB64:$capB64"

            val ciphertextB64 = mlsManager.encrypt(convId, mlsPayload)
            mlsManager.flushState()

            val response = api.sendMessage(
                conversationId = convId,
                request = SendMessageRequest(
                    mlsCiphertextB64 = ciphertextB64,
                    type             = "file",
                    clientMessageId  = generateUuid(),
                    mediaId          = initResp.mediaId,
                    replyToMessageId = replyTo?.serverId
                )
            )

            chatRepository.saveMessage(
                tempMsg.copy(
                    serverId           = response.messageId,
                    status             = MessageStatus.SENT,
                    timestamp          = serverTimestampMs(response.createdAt, fallback = tempMsg.timestamp),
                    text               = caption,
                    mediaId            = initResp.mediaId,
                    encryptionMetadata = encrypted.encryptionMetadataB64,
                    replyToServerId    = replyTo?.serverId
                ),
                convId
            )
            if (replyTo != null && replyTo.serverId.isNotBlank()) {
                _replyContext.update { it + (response.messageId to replyTo.serverId) }
            }
        } catch (e: Exception) {
            println("MemegramDebug [File] ❌ FATAL: ${e::class.simpleName}: ${e.message}")
            chatRepository.saveMessage(
                tempMsg.copy(status = MessageStatus.FAILED, text = S.current.fileSendError(e.message ?: "")),
                convId
            )
            when {
                handlePossibleNotMemberError(e) -> {}
                handleRecipientUnavailableOnSendError(e) -> _error.value = S.current.userDeletedAccountBanner
                handleBlockedByPeerOnSendError(e) -> _error.value = S.current.cannotMessageBlockedByPeer
                else -> _error.value = S.current.fileSendError(e.message ?: "")
            }
        }
    }

    suspend fun downloadFile(message: Message): String? {
        val mediaId = message.mediaId ?: return null
        if (mediaId in _downloadingFiles.value) return null
        if (!message.localFilePath.isNullOrBlank()) return message.localFilePath
        _downloadingFiles.update { it + mediaId }
        return try {
            val resp = api.getMediaDownloadUrl(mediaId)
            val bytes = api.downloadBytesFromUrl(resp.downloadUrl)
            val metadata = resp.encryptionMetadata.takeIf { it.isNotBlank() }
                ?: message.encryptionMetadata.orEmpty()
            if (metadata.isBlank()) {
                throw IllegalStateException("Encryption metadata is missing for media $mediaId")
            }
            val plain = decryptMediaBytes(bytes, metadata)
            val name  = message.fileName ?: "file_$mediaId"
            val mime  = message.fileMime ?: "application/octet-stream"
            val finalPlain = censorIncomingImageIfNeeded(
                mediaId = mediaId,
                imageBytes = plain,
                message = message,
                mime = mime,
                mlPriority = com.example.memegram.ml.MlModelGate.Priority.USER,
            )
            if (isImageForNsfw(message, mime)) {
                _mediaCache.update { it + (mediaId to finalPlain) }
            }
            val saved = saveDownloadedFile(finalPlain, name, mime)
            if (saved == null) {
                throw IllegalStateException("FileSaver returned null (write failed)")
            }
            if (message.serverId.isNotBlank()) {
                val inlineBlob = if (finalPlain.size <= INLINE_BLOB_LIMIT_BYTES) finalPlain else null
                chatRepository.updateMessageLocalFile(message.serverId, saved, inlineBlob)
            }
            saved
        } catch (e: Exception) {
            val reason = "${e::class.simpleName}: ${e.message ?: "unknown"}"
            println("MemegramDebug [downloadFile] ❌ $reason")
            _error.value = "${S.current.fileDownloadError}: $reason"
            null
        } finally {
            _downloadingFiles.update { it - mediaId }
        }
    }

    fun onFileBubbleTap(message: Message) {
        viewModelScope.launch {
            val existing = message.localFilePath
            if (!existing.isNullOrBlank()) {
                openSavedFile(existing, message.fileMime ?: "*/*")
            } else {
                downloadFile(message)
            }
        }
    }

    fun sendVoiceMessageInternal(convId: String, recordResult: AudioRecordResult) {
        if (isPeerBlocked.value) {
            _error.value = S.current.userBlockedSendError
            return
        }
        if (!_isGroupChat.value && _isPeerDeleted.value) {
            _error.value = S.current.userDeletedAccountBanner
            return
        }
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

                if (recordResult.bytes.size.toLong() > MAX_UPLOAD_SIZE_BYTES) {
                    chatRepository.saveMessage(
                        tempMsg.copy(status = MessageStatus.FAILED, text = S.current.voiceSendError("too large")), convId
                    )
                    _error.value = S.current.fileTooLarge(
                        formatSizeBytes(recordResult.bytes.size.toLong()),
                        formatSizeBytes(MAX_UPLOAD_SIZE_BYTES)
                    )
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
                        timestamp          = serverTimestampMs(response.createdAt, fallback = tempMsg.timestamp),
                        mediaId            = initResp.mediaId,
                        encryptionMetadata = encrypted.encryptionMetadataB64,
                        text               = "${recordResult.durationMs}|${recordResult.waveform}",
                        localPreviewBytes  = recordResult.bytes
                    ),
                    convId
                )
            } catch (e: Exception) {
                println("MemegramDebug [Voice]: 🚨 КРИТИЧЕСКАЯ ОШИБКА ОТПРАВКИ: ${e.message}")
                chatRepository.saveMessage(tempMsg.copy(status = MessageStatus.FAILED), convId)
                when {
                    handlePossibleNotMemberError(e) -> {}
                    handleRecipientUnavailableOnSendError(e) -> _error.value = S.current.userDeletedAccountBanner
                    handleBlockedByPeerOnSendError(e) -> _error.value = S.current.cannotMessageBlockedByPeer
                    else -> _error.value = S.current.voiceSendError(e.message ?: "")
                }
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
                val normalized = if (amp <= 300) {
                    0
                } else {
                    val ratio = (kotlin.math.log10(amp.toFloat()) - kotlin.math.log10(300f)) /
                        (kotlin.math.log10(32767f) - kotlin.math.log10(300f))
                    (ratio * 9f).toInt().coerceIn(0, 9)
                }
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
    private val mediaLoadsInFlight = mutableSetOf<String>()

    fun loadMedia(
        mediaId: String,
        encryptionMetadata: String?,
        forceReload: Boolean = false,
        knownMessage: Message? = null,
        mlPriority: com.example.memegram.ml.MlModelGate.Priority = com.example.memegram.ml.MlModelGate.Priority.USER,
    ) {
        if (forceReload) _mediaCache.update { it - mediaId }
        if (!forceReload && _mediaCache.value.containsKey(mediaId)) return
        if (!mediaLoadsInFlight.add(mediaId)) return
        viewModelScope.launch {
            try {
                val resp           = api.getMediaDownloadUrl(mediaId)
                val encryptedBytes = api.downloadBytesFromUrl(resp.downloadUrl)
                val meta           = resp.encryptionMetadata.takeIf { it.isNotBlank() } ?: encryptionMetadata
                val msg = knownMessage ?: _messages.value.firstOrNull { it.mediaId == mediaId }
                val decryptedBytes = if (meta != null) decryptMediaBytes(encryptedBytes, meta) else encryptedBytes
                val finalBytes = censorIncomingImageIfNeeded(
                    mediaId = mediaId,
                    imageBytes = decryptedBytes,
                    message = msg,
                    mime = null,
                    mlPriority = mlPriority,
                )
                _mediaCache.value += (mediaId to finalBytes)
                if (msg != null && (msg.type == "image" || msg.type == "voice") && msg.serverId.isNotBlank()) {
                    chatRepository.updateMessageLocalPreview(msg.serverId, finalBytes)
                }
            } catch (_: Exception) { }
            finally { mediaLoadsInFlight.remove(mediaId) }
        }
    }

    private suspend fun censorIncomingImageIfNeeded(
        mediaId: String,
        imageBytes: ByteArray,
        message: Message?,
        mime: String?,
        mlPriority: com.example.memegram.ml.MlModelGate.Priority,
    ): ByteArray {
        if (message == null || message.isOutgoing || !isImageForNsfw(message, mime)) return imageBytes
        if (!nsfwSettings.filterEnabled.value) {
            nsfwSettings.unmarkMediaProcessed(mediaId)
            return imageBytes
        }
        if (!nsfwService.isModelAvailable()) {
            return imageBytes
        }

        val result = runCatching {
            com.example.memegram.ml.MlModelGate.withModel(
                mlPriority
            ) {
                nsfwService.censorImageIfNeeded(imageBytes, mime)
            }
        }.onSuccess { result ->
            if (result.processed) nsfwSettings.markMediaProcessed(mediaId)
        }.getOrElse {
            if (it is CancellationException) throw it
            println("MemegramDebug [NSFW]: censor failed for media=$mediaId: ${it.message}")
            throw it
        }
        return result.bytes
    }

    private fun isImageForNsfw(message: Message, mime: String?): Boolean {
        if (message.type == "image") return true
        if (message.type != "file") return false
        return mime?.startsWith("image/", ignoreCase = true) == true
            || message.fileMime?.startsWith("image/", ignoreCase = true) == true
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

    fun setMuteUntil(until: Long) {
        val convId = currentConversationId ?: return
        _muteUntil.value = until
        viewModelScope.launch {
            runCatching { chatRepository.setMuteUntil(convId, until) }
                .onFailure { _error.value = it.message }
        }
    }

    fun muteFor(durationMs: Long) {
        val until = when {
            durationMs <= 0L -> 0L
            durationMs == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> Clock.System.now().toEpochMilliseconds() + durationMs
        }
        setMuteUntil(until)
    }

    private var _lastReadServerId: String? = null

    fun clearMessages() {
        val convId = currentConversationId ?: return
        viewModelScope.launch {
            try {
                val myId = myUserId ?: sessionManager.getUserId()
                val myRole = runCatching {
                    api.getConversation(convId).members
                        .firstOrNull { it.userId == myId }
                        ?.role
                }.getOrNull()
                _myRole.value = myRole
                val isAdminOrOwner = myRole == "owner" || myRole == "admin"

                val allMessages = mutableListOf<MessageResponse>()
                var beforeMessageId = ""
                while (true) {
                    val page = api.getMessages(convId, limit = 100, beforeMessageId = beforeMessageId)
                    if (page.isEmpty()) break
                    allMessages += page
                    if (page.size < 100) break
                    beforeMessageId = page.last().id
                }

                val canDeleteAll = allMessages.all { msg ->
                    msg.effectiveSenderId == myId || isAdminOrOwner
                }
                if (!canDeleteAll) {
                    _error.value = S.current.clearHistoryForEveryoneNotAllowed
                    return@launch
                }

                allMessages.forEach { msg ->
                    api.deleteMessage(msg.id, deleteForEveryone = true)
                }
                chatRepository.deleteMessages(convId)
            } catch (e: Exception) {
                _error.value = S.current.deleteError(e.message ?: "")
            }
        }
    }

    fun deleteChat(onDeleted: () -> Unit) {
        val convId = currentConversationId ?: return
        viewModelScope.launch {
            try {
                api.deleteConversation(convId)
                try { mlsManager.deleteLocalGroup(convId) } catch (_: Exception) {}
                chatRepository.deleteChat(convId)
                mlsManager.flushState()
                if (ActiveChatCoordinator.conversationId == convId) {
                    ActiveChatCoordinator.conversationId = null
                }
                onDeleted()
            } catch (e: Exception) {
                _error.value = S.current.deleteError(e.message ?: "")
            }
        }
    }

    fun setReplyTo(message: Message?) { _replyingTo.value = message }
    fun clearReply() { _replyingTo.value = null }

    fun translateMessage(message: Message, forcedSourceLang: String? = null) {
        val serverId = message.serverId
        if (serverId.isBlank()) return
        var added = false
        _translationProgress.update { current ->
            if (serverId in current) current
            else {
                added = true
                current + (serverId to 0f)
            }
        }
        if (!added) {
            println("MemegramDebug [Translate]: skip duplicate request for $serverId")
            return
        }
        viewModelScope.launch {
            try {
                val appLang = settings.getString("app_language", "en")
                val targetLang = translationSettings.getEffectiveTargetLang(appLang)
                val textToTranslate = if (message.isTranslated) message.originalText ?: message.text else message.text
                println("MemegramDebug [Translate]: text='${textToTranslate.take(50)}' src=$forcedSourceLang tgt=$targetLang")
                val result = com.example.memegram.ml.MlModelGate.withModel(
                    com.example.memegram.ml.MlModelGate.Priority.USER
                ) {
                    translationService.translate(
                        text = textToTranslate,
                        sourceLang = forcedSourceLang,
                        targetLang = targetLang,
                        onProgress = { progress -> updateTranslationProgress(serverId, progress) }
                    )
                }
                println("MemegramDebug [Translate]: result='${result.translatedText.take(50)}' detectedLang=${result.detectedSourceLang}")
                if (result.translatedText != textToTranslate) {
                    chatRepository.updateMessageTranslation(
                        message.serverId, result.translatedText, result.detectedSourceLang
                    )
                } else {
                    _error.value = S.current.translationNotAvailable
                }
            } catch (e: Exception) {
                println("MemegramDebug [Translate]: Error: ${e.message}")
                _error.value = S.current.translationNotAvailable
            } finally {
                finishTranslationProgress(serverId)
                _translationProgress.update { it - serverId }
            }
        }
    }

    fun revertTranslation(message: Message) {
        viewModelScope.launch {
            chatRepository.revertMessageTranslation(message.serverId)
        }
    }

    fun showCachedTranslation(message: Message) {
        viewModelScope.launch {
            chatRepository.showCachedTranslation(message.serverId)
        }
    }

    // ── Voice transcription (Whisper) ────────────────────────────────

    fun toggleTranscriptionVisibility(serverId: String) {
        if (serverId.isBlank()) return
        _visibleTranscriptions.update { current ->
            if (serverId in current) current - serverId else current + serverId
        }
    }

    fun transcribeMessage(message: Message) {
        val serverId = message.serverId
        println("MemegramDebug [Transcribe]: click serverId=$serverId type=${message.type} mediaId=${message.mediaId}")
        if (serverId.isBlank()) {
            println("MemegramDebug [Transcribe]: skip, blank serverId")
            return
        }
        if (message.type != "voice") {
            println("MemegramDebug [Transcribe]: skip, not a voice message")
            return
        }

        if (!message.transcribedText.isNullOrBlank() &&
            message.transcriptionStatus == TranscriptionStatus.DONE) {
            _visibleTranscriptions.update { it + serverId }
            return
        }

        if (!TranscriptionProgressTracker.tryQueue(serverId)) return

        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    message.localPreviewBytes
                        ?: message.mediaId?.let { _mediaCache.value[it] }
                        ?: message.localFilePath?.takeIf { it.isNotBlank() }?.let {
                            com.example.memegram.audio.readLocalAudioFile(it)
                        }
                }
                if (bytes == null || bytes.isEmpty()) {
                    println("MemegramDebug [Transcribe]: no local audio bytes for serverId=$serverId")
                    chatRepository.setTranscriptionStatus(serverId, TranscriptionStatus.FAILED)
                    _error.value = S.current.transcriptionFailed
                    return@launch
                }
                println("MemegramDebug [Transcribe]: start Whisper bytes=${bytes.size} serverId=$serverId")

                val result = com.example.memegram.ml.MlModelGate.withModel(
                    priority = com.example.memegram.ml.MlModelGate.Priority.USER,
                    onStarted = { TranscriptionProgressTracker.markStarted(serverId) }
                ) {
                    try {
                        chatRepository.setTranscriptionStatus(serverId, TranscriptionStatus.IN_PROGRESS)
                        val transcriptionResult = transcriptionService.transcribe(
                            audioBytes = bytes,
                            language = null,
                            onProgress = { p -> TranscriptionProgressTracker.update(serverId, p.fraction) }
                        )
                        chatRepository.updateMessageTranscription(
                            serverId = serverId,
                            transcribedText = transcriptionResult.text.trim().ifBlank { S.current.transcriptionNoSpeech },
                            transcribedLang = transcriptionResult.language.takeIf { it.isNotBlank() }
                        )
                        transcriptionResult
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        chatRepository.setTranscriptionStatus(serverId, TranscriptionStatus.FAILED)
                        throw e
                    } finally {
                        TranscriptionProgressTracker.finish(serverId)
                    }
                }

                _visibleTranscriptions.update { it + serverId }
            } catch (e: CancellationException) {
                if (!TranscriptionProgressTracker.isActive(serverId)) {
                    withContext(NonCancellable) {
                        TranscriptionProgressTracker.clear(serverId)
                    }
                }
                throw e
            } catch (e: Exception) {
                println("MemegramDebug [Transcribe]: error ${e.message}")
                if (!TranscriptionProgressTracker.isActive(serverId)) {
                    chatRepository.setTranscriptionStatus(serverId, TranscriptionStatus.FAILED)
                }
                _error.value = S.current.transcriptionFailed
            } finally {
                if (!TranscriptionProgressTracker.isActive(serverId)) {
                    withContext(NonCancellable) {
                        TranscriptionProgressTracker.clear(serverId)
                    }
                }
            }
        }
    }

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
    
    fun deleteFailedMessage(message: Message) {
        if (message.status != MessageStatus.FAILED) return
        viewModelScope.launch {
            val key = message.serverId.takeIf { it.isNotBlank() } ?: "temp_${message.id}"
            chatRepository.deleteMessageByServerId(key)
        }
    }

    fun resendMessage(message: Message) {
        val convId = currentConversationId ?: return
        if (message.status != MessageStatus.FAILED) return
        if (message.type != "text") {
            _error.value = S.current.resendUnsupported
            return
        }
        if (!_isGroupChat.value && _isPeerDeleted.value) {
            _error.value = S.current.userDeletedAccountBanner
            return
        }
        viewModelScope.launch {
            val oldKey = message.serverId.takeIf { it.isNotBlank() } ?: "temp_${message.id}"
            chatRepository.deleteMessageByServerId(oldKey)
            sendTextMessageInternal(convId, message.text)
        }
    }

    private fun encodeBase64Utf8(s: String): String =
        kotlin.io.encoding.Base64.encode(s.encodeToByteArray())

    fun blockPeer() {
        val peerId = peerUserId ?: return
        val convId = currentConversationId
        viewModelScope.launch {
            try {
                api.blockUser(com.example.memegram.data.models.BlockUserRequest(peerId))
                blockedUsersCache.add(peerId)
                if (convId != null) {
                    val chat = chatRepository.getChatById(convId)
                    if (chat != null && chat.unreadCount > 0) {
                        chatRepository.saveChat(chat.copy(unreadCount = 0))
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun unblockPeer() {
        val peerId = peerUserId ?: return
        viewModelScope.launch {
            try {
                api.unblockUser(peerId)
                blockedUsersCache.remove(peerId)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (ActiveChatCoordinator.conversationId == currentConversationId) {
            ActiveChatCoordinator.conversationId = null
        }
        sseJob?.cancel()
        typingJob?.cancel()
        dbObserveJob?.cancel()
    }
}

object ChatScrollCache {
    private val positions = mutableMapOf<String, Pair<Int, Int>>()

    fun save(conversationId: String, index: Int, offset: Int) {
        positions[conversationId] = index to offset
    }

    fun restore(conversationId: String): Pair<Int, Int>? = positions[conversationId]
}
