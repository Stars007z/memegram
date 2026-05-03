package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.auth.SessionRefresher
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.CommitGroupChangeRequest
import com.example.memegram.data.models.LeaveConversationRequest
import com.example.memegram.data.models.SseEvent
import com.example.memegram.data.network.ApiException
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.localization.S
import com.example.memegram.mls.MlsCommitProcessResult
import com.example.memegram.mls.MlsManager
import com.example.memegram.mls.MlsManager.Companion.BATCH_KEY_PACKAGES
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.russhwolf.settings.Settings
import kotlin.time.Clock

class ChatsViewModel(
    private val sessionManager: SessionManager,
    private val api: ApiService,
    private val sessionRefresher: SessionRefresher,
    private val mlsManager: MlsManager,
    private val chatRepository: ChatRepository,
    private val blockedUsersCache: BlockedUsersCache,
    private val profileRepository: com.example.memegram.data.repository.ProfileRepository,
    private val settings: Settings,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _blockedConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedConversationIds: StateFlow<Set<String>> = _blockedConversationIds.asStateFlow()

    private val _selectedChatIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedChatIds: StateFlow<Set<String>> = _selectedChatIds.asStateFlow()
    val isSelectionMode: StateFlow<Boolean> = _selectedChatIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleSelection(conversationId: String) {
        _selectedChatIds.update { current ->
            if (conversationId in current) current - conversationId else current + conversationId
        }
    }

    fun clearSelection() { _selectedChatIds.value = emptySet() }

    fun isMuted(chat: ChatModel): Boolean =
        chat.muteUntil == Long.MAX_VALUE || chat.muteUntil > Clock.System.now().toEpochMilliseconds()

    fun muteChats(conversationIds: Set<String>, durationMs: Long) {
        if (conversationIds.isEmpty()) return
        viewModelScope.launch {
            val until = when {
                durationMs <= 0L -> 0L
                durationMs == Long.MAX_VALUE -> Long.MAX_VALUE
                else -> Clock.System.now().toEpochMilliseconds() + durationMs
            }
            chatRepository.setMuteUntilForIds(conversationIds.toList(), until)
            clearSelection()
        }
    }

    fun deleteSelectedChats() {
        val ids = _selectedChatIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val snapshot = chats.value.associateBy { it.conversationId }
            for (id in ids) {
                val chat = snapshot[id] ?: chatRepository.getChatById(id)
                val isGroup = chat?.isGroup ?: false
                try {
                    if (isGroup) {
                        try {
                            api.deleteConversation(id)
                        } catch (_: Exception) {
                            try {
                                api.leaveConversation(id, LeaveConversationRequest(commitData = ""))
                            } catch (_: Exception) {}
                        }
                    } else {
                        api.deleteConversation(id)
                    }
                } catch (_: Exception) {}

                try { mlsManager.deleteLocalGroup(id) } catch (_: Exception) {}
                chatRepository.deleteChat(id)
            }
            mlsManager.flushState()
            clearSelection()
        }
    }

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

    val searchMessageResults: StateFlow<List<ChatSearchResult>> = combine(
        chatRepository.getAllChatsFlow(),
        chatRepository.getAllMessagesFlow(),
        _searchQuery,
        blockedUsersCache.blockedIds
    ) { allChats, allMessages, query, blockedIds ->
        val q = query.trim()
        if (q.isBlank()) return@combine emptyList()

        val chatsById = allChats.associateBy { it.conversationId }
        val myId = sessionManager.getUserId()
        val results = mutableListOf<ChatSearchResult>()

        for (stored in allMessages) {
            if (results.size >= 200) break
            val chat = chatsById[stored.conversationId] ?: continue
            val msg = stored.message
            val senderId = msg.senderUserId
            if (senderId != null && senderId != myId && senderId in blockedIds) continue
            if (chat.peerUserId != null && chat.peerUserId in blockedIds) continue

            val displayText = searchDisplayText(msg)
            val searchableText = buildString {
                if (msg.text.isNotBlank()) append(msg.text) else append(displayText)
                append('\n')
                msg.fileName?.let { append('\n').append(it) }
            }
            if (!searchableText.contains(q, ignoreCase = true)) continue

            val profile = senderId
                ?.takeIf { it.isNotBlank() && it != myId }
                ?.let { profileRepository.getCached(it) }
            val senderName = when {
                msg.isOutgoing -> null
                chat.isGroup -> profile?.username ?: senderId?.takeIf { it.isNotBlank() }?.let { "User_${it.take(4)}" }
                else -> chat.name
            }
            val senderAvatar = when {
                msg.isOutgoing -> null
                chat.isGroup -> profile?.avatarMediaId
                else -> chat.avatarMediaId
            }

            results += ChatSearchResult(
                chat = chat,
                message = msg,
                senderName = senderName,
                senderAvatarMediaId = senderAvatar,
                displayText = displayText
            )
        }

        results
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun searchDisplayText(message: Message): String = when {
        message.type == "voice" -> if (message.text.isNotBlank()) message.text else "🎤 ${S.current.voiceMessage}"
        message.type == "file" -> if (message.text.isNotBlank()) message.text else "📎 ${message.fileName ?: S.current.file}"
        message.type == "image" && message.text.isBlank() -> "📸 ${S.current.photo}"
        message.type == "image" -> message.text
        message.text.isNotBlank() -> message.text
        else -> S.current.messageDeleted
    }

    private var sseJob: Job? = null
    private var pollingJob: Job? = null

    private val peerCache = mutableMapOf<String, String>()

    private val blockedUnreadOffset = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch {
            initMls()
            loadChatsInternal()
            startPolling()
            startGlobalMlsSync()
        }
        viewModelScope.launch { blockedUsersCache.load() }
        viewModelScope.launch {
            blockedUsersCache.blockedIds.collect { blockedIds ->
                _blockedConversationIds.value = peerCache.entries
                    .filter { (_, peerId) -> peerId in blockedIds }
                    .map { (convId, _) -> convId }
                    .toSet()
            }
        }
    }

    private suspend fun initMls() {
        try {
            if (!ensureFreshSession()) return
            mlsManager.initialize()
            if (mlsManager.needsKeyPackages()) {
                val packages = mlsManager.generateKeyPackages(BATCH_KEY_PACKAGES)
                api.uploadKeyPackages(packages)
            }
            processPendingWelcomes()
        } catch (_: Exception) {
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
            if (!ensureFreshSession()) return
            val response = api.getConversations()
            val currentUserId = sessionManager.getUserId()

            val newChatsList = response.items.map { conv ->
                val localChat = chatRepository.getChatById(conv.id)
                var chatName = conv.name?.takeIf { it.isNotBlank() } ?: "Собеседник"
                var peerAvatarMediaId: String? = null
                var dmPeerUserId: String? = DeletedPeerStore.conversationPeerId(settings, conv.id)
                    ?: localChat?.peerUserId

                if (conv.type == "direct") {
                    try {
                        val peerId = peerCache[conv.id] ?: dmPeerUserId ?: run {
                            val details = api.getConversation(conv.id)
                            val peer = details.members.find { it.userId != currentUserId }
                            peer?.userId?.also { peerCache[conv.id] = it }
                        }
                        dmPeerUserId = peerId
                        peerId?.let { peerCache[conv.id] = it }
                        if (peerId != null) {
                            val profile = profileRepository.getOrFetch(peerId, forceRefresh = true)
                            if (profile != null) {
                                val deleted = profile.isDeleted || DeletedPeerStore.isDeleted(settings, conv.id, peerId)
                                if (deleted) DeletedPeerStore.markConversationDeleted(settings, conv.id, peerId)
                                chatName = if (deleted) {
                                    com.example.memegram.localization.S.current.deletedAccountTitle
                                } else {
                                    profile.username?.takeIf { it.isNotBlank() } ?: "User_${peerId.take(4)}"
                                }
                                peerAvatarMediaId = if (deleted) null else profile.avatarMediaId
                            } else if (DeletedPeerStore.isDeleted(settings, conv.id, peerId)) {
                                chatName = com.example.memegram.localization.S.current.deletedAccountTitle
                                peerAvatarMediaId = null
                            }
                        } else if (DeletedPeerStore.isConversationDeleted(settings, conv.id)) {
                            chatName = com.example.memegram.localization.S.current.deletedAccountTitle
                            peerAvatarMediaId = null
                        }
                    } catch (_: Exception) {}
                }

                val localMessages = chatRepository.getMessagesOnce(conv.id)
                val localLastMessage = localMessages.lastOrNull()

                val isMine = localLastMessage?.isOutgoing ?: false
                val isGroup = conv.type != "direct"

                val serverUnread = conv.unreadCount ?: 0
                if (serverUnread == 0) blockedUnreadOffset.remove(conv.id)
                val blockedIdsNow = blockedUsersCache.blockedIds.value
                val adjustedGroupUnread: Int = if (isGroup && serverUnread > 0) {
                    val byOffset = blockedUnreadOffset[conv.id] ?: 0
                    val byLocal = if (blockedIdsNow.isNotEmpty()) {
                        val tail = localMessages
                            .asSequence()
                            .filter { !it.isOutgoing }
                            .toList()
                            .takeLast(serverUnread)
                        tail.count { msg ->
                            val s = msg.senderUserId
                            s != null && s in blockedIdsNow
                        }
                    } else 0
                    val correction = maxOf(byOffset, byLocal)
                    (serverUnread - correction).coerceAtLeast(0)
                } else serverUnread

                var senderName: String? = null
                var lastSenderAvatarMediaId: String? = null

                if (isGroup && !isMine && localLastMessage != null) {
                    val senderId = localLastMessage.senderUserId
                    if (senderId != null) {
                        try {
                            val senderProfile = profileRepository.getOrFetch(senderId)
                            if (senderProfile != null) {
                                senderName = senderProfile.username?.takeIf { it.isNotBlank() }
                                    ?: "User_${senderId.take(4)}"
                                lastSenderAvatarMediaId = senderProfile.avatarMediaId
                            } else {
                                senderName = "User_${senderId.take(4)}"
                            }
                        } catch (_: Exception) {
                            senderName = "User_${senderId.take(4)}"
                        }
                    }
                }

                val displayLastMessage = when {
                    localLastMessage?.type == "voice" -> "🎤 Голосовое сообщение"
                    localLastMessage?.type == "image" && localLastMessage.text.isBlank() -> "📸 Фото"
                    localLastMessage != null && localLastMessage.text.isNotBlank() -> localLastMessage.text
                    conv.lastMessageType == "voice" -> "🎤 Голосовое сообщение"
                    conv.lastMessageType == "image" -> "📸 Фото"
                    conv.lastMessageType == "text" -> "Сообщение"
                    else -> "Новый чат"
                }

                ChatModel(
                    id                      = conv.id.hashCode(),
                    conversationId          = conv.id,
                    name                    = chatName,
                    lastMessage             = displayLastMessage,
                    timestamp               = conv.lastActivityAt * 1000,
                    unreadCount             = when {
                        !isGroup && dmPeerUserId != null && blockedUsersCache.isBlocked(dmPeerUserId) -> 0
                        isGroup -> adjustedGroupUnread
                        else -> serverUnread
                    },
                    isLastMessageMine       = isMine,
                    lastSenderName          = senderName,
                    avatarMediaId           = if (!isGroup && DeletedPeerStore.isConversationDeleted(settings, conv.id)) {
                        null
                    } else {
                        peerAvatarMediaId ?: conv.avatarMediaId?.takeIf { it.isNotBlank() }
                    },
                    lastSenderAvatarMediaId = lastSenderAvatarMediaId,
                    peerUserId              = dmPeerUserId,
                    isGroup                 = isGroup
                )
            }

            chatRepository.saveChats(newChatsList)
            subscribeToGlobalEvents(newChatsList.map { it.conversationId })

            val blockedIds = blockedUsersCache.blockedIds.value
            _blockedConversationIds.value = peerCache.entries
                .filter { (_, peerId) -> peerId in blockedIds }
                .map { (convId, _) -> convId }
                .toSet()

        } catch (_: Exception) {
            if (!silent) _error.value = "Не удалось загрузить чаты"
        } finally {
            if (!silent) _isLoading.value = false
        }
    }

    private suspend fun processPendingWelcomes() {
        try {
            if (!ensureFreshSession()) return
            val welcomes = api.getPendingWelcomes()
            var hasNew = false
            for (w in welcomes) {
                val convId = w.conversationId
                if (mlsManager.isChatMlsBroken(convId)) {
                    println("MemegramDebug [Welcome]: ack stale Welcome for MLS-broken conv=$convId")
                    runCatching { api.ackWelcome(w.id) }
                        .onFailure { println("MemegramDebug [Welcome]: ack stale failed: ${it.message}") }
                    continue
                }

                if (mlsManager.hasGroup(convId)) {
                    runCatching { api.ackWelcome(w.id) }
                    continue
                }

                try {
                    mlsManager.processWelcome(convId, w.welcomeDataB64)
                    api.ackWelcome(w.id)

                    val realEpoch = mlsManager.getRealMlsEpoch(convId)
                    mlsManager.updateGroupEpoch(convId, realEpoch)
                    println("MemegramDebug [Welcome]: Новичку установлена реальная MLS-эпоха = $realEpoch")

                    hasNew = true
                } catch (e: Exception) {
                    if (mlsManager.isChatMlsBroken(convId)) {
                        println("MemegramDebug [Welcome]: stale Welcome detected, ack and stop retry conv=$convId")
                        runCatching { api.ackWelcome(w.id) }
                            .onFailure { println("MemegramDebug [Welcome]: ack stale failed: ${it.message}") }
                    } else {
                        println("MemegramDebug [Welcome]: process failed for conv=$convId: ${e.message}")
                    }
                }
            }
            if (hasNew) loadChatsInternal(silent = true)
        } catch (_: Exception) {}
    }

    private fun subscribeToGlobalEvents(conversationIds: List<String>) {
        val newIdsKey = conversationIds.sorted().joinToString(",")
        if (_lastSubscribedKey == newIdsKey && sseJob?.isActive == true) return
        _lastSubscribedKey = newIdsKey

        sseJob?.cancel()
        if (conversationIds.isEmpty()) return

        val idsParam = conversationIds.joinToString(",")
        sseJob = viewModelScope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    if (!ensureFreshSession()) {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                        continue
                    }
                    println("MemegramDebug [ChatsVM]: SSE global подключаемся")
                    api.subscribeToConversation(idsParam).collect { event ->
                        handleGlobalEvent(event)
                        backoffMs = 1_000L
                    }
                    println("MemegramDebug [ChatsVM]: SSE global стрим завершён, retry через ${backoffMs}мс")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isUnauthorized()) {
                        println("MemegramDebug [ChatsVM]: SSE global 401, refresh token before retry")
                        if (ensureFreshSession(force = true)) backoffMs = 1_000L
                    }
                    println("MemegramDebug [ChatsVM]: SSE global ошибка (${e.message}), retry через ${backoffMs}мс")
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun ensureFreshSession(force: Boolean = false): Boolean {
        if (!force && !sessionManager.isTokenExpired) return true
        return sessionRefresher.refreshIfNeededAwait(force = force)
    }

    private fun Throwable.isUnauthorized(): Boolean =
        this is ApiException && status == HttpStatusCode.Unauthorized

    private var _lastSubscribedKey: String = ""

    private suspend fun handleGlobalEvent(event: SseEvent) {
        val convId = event.conversationId
        if (convId.isBlank()) return

        when (event.type) {
            "new_message" -> {
                val currentUserId = sessionManager.getUserId()
                val isMine = event.data?.senderUserId == currentUserId

                val senderUid = event.data?.senderUserId
                if (!isMine && senderUid != null && blockedUsersCache.isBlocked(senderUid)) {
                    println("MemegramDebug [ChatsVM]: drop new_message from blocked user $senderUid")
                    val msgId = event.data?.id
                    if (!msgId.isNullOrBlank()) {
                        viewModelScope.launch {
                            runCatching { api.markAsRead(convId, com.example.memegram.data.models.MarkAsReadRequest(msgId)) }
                        }
                    }
                    blockedUnreadOffset[convId] = (blockedUnreadOffset[convId] ?: 0) + 1
                    val chat = chatRepository.getChatById(convId)
                    if (chat != null && chat.unreadCount > 0) {
                        chatRepository.saveChat(chat.copy(unreadCount = 0))
                    }
                    return
                }

                if (convId == ActiveChatCoordinator.conversationId) {
                    blockedUnreadOffset.remove(convId)
                    val chat = chatRepository.getChatById(convId)
                    if (chat != null) {
                        chatRepository.saveChat(
                            chat.copy(
                                timestamp   = (event.data?.createdAt?.let { it * 1000L }) ?: chat.timestamp,
                                unreadCount = if (isMine) chat.unreadCount else 0
                            )
                        )
                    }
                    return
                }

                val decryptedText = try {
                    mlsManager.decrypt(convId, event.data?.mlsCiphertextB64 ?: "")
                } catch (_: Exception) { null }

                if (decryptedText == null) {
                    println("MemegramDebug [ChatsVM]: drop undecryptable message in conv=$convId")
                    return
                }

                mlsManager.flushState()
                chatRepository.saveMessage(
                    Message(
                        id           = event.data?.id.hashCode(),
                        serverId     = event.data?.id ?: "",
                        text         = decryptedText,
                        isOutgoing   = isMine,
                        timestamp    = (event.data?.createdAt?.let { it * 1000L })
                            ?: Clock.System.now().toEpochMilliseconds(),
                        status       = MessageStatus.SENT,
                        senderUserId = event.data?.senderUserId,
                        replyToServerId = event.data?.replyToMessageId?.takeIf { it.isNotBlank() }
                    ),
                    convId
                )

                val chat = chatRepository.getChatById(convId)
                if (chat != null) {
                    var senderName: String? = null
                    var senderAvatarMediaId: String? = null
                    val senderId = event.data?.senderUserId
                    if (!isMine && senderId != null) {
                        try {
                            val profile = profileRepository.getOrFetch(senderId)
                            if (profile != null) {
                                senderName = profile.username?.takeIf { it.isNotBlank() }
                                    ?: "User_${senderId.take(4)}"
                                senderAvatarMediaId = profile.avatarMediaId
                            } else {
                                senderName = "User_${senderId.take(4)}"
                            }
                        } catch (_: Exception) {
                            senderName = "User_${senderId.take(4)}"
                        }
                    }
                    chatRepository.saveChat(
                        chat.copy(
                            lastMessage             = decryptedText,
                            timestamp               = (event.data?.createdAt?.let { it * 1000L }) ?: chat.timestamp,
                            unreadCount             = if (isMine) chat.unreadCount else chat.unreadCount + 1,
                            isLastMessageMine       = isMine,
                            lastSenderName          = senderName,
                            lastSenderAvatarMediaId = senderAvatarMediaId
                        )
                    )
                } else {
                    loadChatsInternal(silent = true)
                }
            }
            "epoch_changed" -> {
                if (convId.isNotBlank()) {
                    syncGroupCommitsQuietly(convId)
                }
            }
            "member_left" -> {
                val leftUserId = event.data?.userId
                val myUserId = sessionManager.getUserId()
                val reason = event.data?.reason
                val convType = event.data?.conversationType

                if (leftUserId == null || leftUserId == myUserId) return

                val localChat = chatRepository.getChatById(convId)
                val isDirectChat = convType == "direct" || localChat?.isGroup == false

                if (isDirectChat || reason == "account_deleted") {
                    viewModelScope.launch {
                        if (isDirectChat) {
                            DeletedPeerStore.markConversationDeleted(settings, convId, leftUserId)
                            profileRepository.upsert(
                                com.example.memegram.data.models.UserProfileResponse(
                                    id = leftUserId,
                                    isDeleted = true,
                                )
                            )
                            val chat = localChat ?: chatRepository.getChatById(convId)
                            if (chat != null && !chat.isGroup) {
                                chatRepository.saveChat(
                                    chat.copy(
                                        name = com.example.memegram.localization.S.current.deletedAccountTitle,
                                        avatarMediaId = null,
                                        peerUserId = leftUserId,
                                    )
                                )
                            }
                        }
                        try {
                            profileRepository.refresh(leftUserId)
                        } catch (_: Exception) {}
                    }
                    if (convType != "direct" && mlsManager.hasGroup(convId)) {
                        handleMemberLeftRemoval(convId, leftUserId)
                    }
                    return
                }

                if (mlsManager.hasGroup(convId)) {
                    handleMemberLeftRemoval(convId, leftUserId)
                }
            }
            "member_kicked" -> {
                val kickedUserId = event.data?.userId
                val myUserId = sessionManager.getUserId()
                if (kickedUserId == null) return

                if (kickedUserId == myUserId) {
                    handleSelfKicked(convId)
                } else if (mlsManager.hasGroup(convId)) {
                    handleMemberLeftRemoval(convId, kickedUserId)
                }
            }
            "role_changed" -> {
                val userId = event.data?.userId
                val newRole = event.data?.newRole
                println("MemegramDebug [ChatsVM]: role_changed: user=$userId, newRole=$newRole in conv=$convId")
            }
            "conversation_deleted" -> {
                if (event.data?.reason == "account_deleted") {
                    println("MemegramDebug [ChatsVM]: ignoring conversation_deleted with reason=account_deleted (legacy)")
                    return
                }
                println("MemegramDebug [ChatsVM]: conversation_deleted event for conv=$convId — purging locally")
                try { mlsManager.deleteLocalGroup(convId) } catch (_: Exception) {}
                chatRepository.deleteChat(convId)
                mlsManager.flushState()
            }
        }
    }

    private fun startGlobalMlsSync() {
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                try {
                    val currentChats = chats.value
                    for (chat in currentChats) {
                        val convId = chat.conversationId
                        if (mlsManager.hasGroup(convId, log = false)) {
                            syncGroupCommitsQuietly(convId)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleMemberLeftRemoval(conversationId: String, leftUserId: String) {
        viewModelScope.launch {
            try {
                println("MemegramDebug [ChatsVM]: member_left event: user=$leftUserId in conv=$conversationId")

                syncGroupCommitsQuietly(conversationId)

                val commitB64 = try {
                    mlsManager.removeMember(conversationId, leftUserId)
                } catch (e: Exception) {
                    println("MemegramDebug [ChatsVM]: removeMember failed (already removed?): ${e.message}")
                    syncGroupCommitsQuietly(conversationId)
                    return@launch
                }

                val serverEpoch = mlsManager.getCommitCursor(conversationId)
                    ?: runCatching { api.getConversation(conversationId).mlsGroup?.currentEpoch }.getOrNull()
                    ?: mlsManager.getGroupEpoch(conversationId)
                val nextEpoch = (serverEpoch + 1).toInt()

                try {
                    api.commitGroupChange(
                        conversationId,
                        CommitGroupChangeRequest(
                            commitData = commitB64,
                            newEpoch = nextEpoch,
                            removedDeviceIds = emptyList()
                        )
                    )

                    mlsManager.mergePendingCommit(conversationId)
                    mlsManager.updateCommitCursor(conversationId, nextEpoch.toLong())
                    val realEpoch = mlsManager.getRealMlsEpoch(conversationId)
                    mlsManager.updateGroupEpoch(conversationId, realEpoch)
                    mlsManager.flushState()

                    println("MemegramDebug [ChatsVM]: Remove commit sent successfully, epoch=$nextEpoch")
                } catch (e: Exception) {
                    println("MemegramDebug [ChatsVM]: commitGroupChange failed (epoch conflict?): ${e.message}")
                    try { mlsManager.clearPendingCommit(conversationId) } catch (_: Exception) {}
                    syncGroupCommitsQuietly(conversationId)
                }
            } catch (e: Exception) {
                println("MemegramDebug [ChatsVM]: handleMemberLeftRemoval error: ${e.message}")
            }
        }
    }

    private fun handleSelfKicked(conversationId: String) {
        viewModelScope.launch {
            try {
                println("MemegramDebug [ChatsVM]: I was kicked from conv=$conversationId — cleaning up")

                try { mlsManager.deleteLocalGroup(conversationId) } catch (_: Exception) {}

                chatRepository.deleteChat(conversationId)
                mlsManager.flushState()

                println("MemegramDebug [ChatsVM]: ✅ Self-kick cleanup done for conv=$conversationId")
            } catch (e: Exception) {
                println("MemegramDebug [ChatsVM]: handleSelfKicked error: ${e.message}")
            }
        }
    }

    private suspend fun syncGroupCommitsQuietly(conversationId: String, justProcessedWelcome: Boolean = false) {
        try {
            val cursorEpoch = mlsManager.getCommitCursor(conversationId)
                ?: mlsManager.getGroupEpoch(conversationId)
            val commits = api.getPendingCommits(conversationId, cursorEpoch)

            if (commits.isNotEmpty()) {
                if (justProcessedWelcome) {
                    val realEpoch = mlsManager.getRealMlsEpoch(conversationId)
                    mlsManager.updateGroupEpoch(conversationId, realEpoch)
                    println("MemegramDebug [Welcome]: Синхронизирована metadata-эпоха с реальной MLS = $realEpoch")
                } else {
                    val newCommits = commits.filter { it.epoch > cursorEpoch }
                    if (newCommits.isNotEmpty()) {
                        newCommits.sortedBy { it.epoch }.forEach { commit ->
                            val result = try {
                                mlsManager.processCommitResult(conversationId, commit.commitDataB64)
                            } catch (e: Exception) {
                                println("MemegramDebug [BackgroundSync]: ❌ Ошибка коммита ${commit.epoch}: ${e.message}")
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
                    }
                }
                mlsManager.flushState()
            }
        } catch (_: Exception) {}
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
}
