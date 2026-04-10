package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.CommitGroupChangeRequest
import com.example.memegram.data.models.LogoutRequest
import com.example.memegram.data.models.SseEvent
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager
import com.example.memegram.mls.MlsManager.Companion.BATCH_KEY_PACKAGES
import kotlinx.coroutines.CancellationException
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

    private val peerCache = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            initMls()
            loadChatsInternal()
            startPolling()
            startGlobalMlsSync()
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
            val response = api.getConversations()
            val currentUserId = sessionManager.getUserId()

            val newChatsList = response.items.map { conv ->
                var chatName = conv.name?.takeIf { it.isNotBlank() } ?: "Собеседник"

                if (conv.type == "direct") {
                    try {
                        val peerId = peerCache[conv.id] ?: run {
                            val details = api.getConversation(conv.id)
                            val peer = details.members.find { it.userId != currentUserId }
                            peer?.userId?.also { peerCache[conv.id] = it }
                        }
                        if (peerId != null) {
                            val profile = profileCache[peerId]
                                ?: api.getUserById(peerId).also { profileCache[peerId] = it }
                            chatName = profile.username?.takeIf { it.isNotBlank() } ?: "User_${peerId.take(4)}"
                        }
                    } catch (_: Exception) {}
                }

                val localMessages = chatRepository.getMessagesOnce(conv.id)
                val localLastMessage = localMessages.lastOrNull()

                val isMine = localLastMessage?.isOutgoing ?: false
                val isGroup = conv.type != "direct"

                val senderName = if (isGroup && !isMine && localLastMessage != null) {
                    "?"
                } else null

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
                    id                = conv.id.hashCode(),
                    conversationId    = conv.id,
                    name              = chatName,
                    lastMessage       = displayLastMessage,
                    timestamp         = conv.lastActivityAt * 1000,
                    unreadCount       = conv.unreadCount,
                    isLastMessageMine = isMine,
                    lastSenderName    = senderName
                )
            }

            chatRepository.saveChats(newChatsList)
            subscribeToGlobalEvents(newChatsList.map { it.conversationId })

        } catch (_: Exception) {
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

                    val realEpoch = mlsManager.getRealMlsEpoch(w.conversationId)
                    mlsManager.updateGroupEpoch(w.conversationId, realEpoch)
                    println("MemegramDebug [Welcome]: Новичку установлена реальная MLS-эпоха = $realEpoch")

                    hasNew = true
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
                    println("MemegramDebug [ChatsVM]: SSE global подключаемся")
                    api.subscribeToConversation(idsParam).collect { event ->
                        handleGlobalEvent(event)
                        backoffMs = 1_000L
                    }
                    println("MemegramDebug [ChatsVM]: SSE global стрим завершён, retry через ${backoffMs}мс")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("MemegramDebug [ChatsVM]: SSE global ошибка (${e.message}), retry через ${backoffMs}мс")
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private var _lastSubscribedKey: String = ""

    private suspend fun handleGlobalEvent(event: SseEvent) {
        val convId = event.conversationId
        if (convId.isBlank()) return

        when (event.type) {
            "new_message" -> {
                val currentUserId = sessionManager.getUserId()
                val isMine = event.data?.senderUserId == currentUserId

                if (convId == ActiveChatCoordinator.conversationId) {
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

                if (decryptedText != null) {
                    mlsManager.flushState()
                    chatRepository.saveMessage(
                        Message(
                            id         = event.data?.id.hashCode(),
                            serverId   = event.data?.id ?: "",
                            text       = decryptedText,
                            isOutgoing = isMine,
                            timestamp  = (event.data?.createdAt?.let { it * 1000L })
                                ?: Clock.System.now().toEpochMilliseconds(),
                            status     = MessageStatus.SENT
                        ),
                        convId
                    )
                }

                val chat = chatRepository.getChatById(convId)
                if (chat != null) {
                    val senderName = if (!isMine && chat.name != "Собеседник") chat.name else null
                    chatRepository.saveChat(
                        chat.copy(
                            lastMessage = decryptedText ?: if (isMine) "📨" else "🔒",
                            timestamp   = (event.data?.createdAt?.let { it * 1000L }) ?: chat.timestamp,
                            unreadCount = if (isMine) chat.unreadCount else chat.unreadCount + 1,
                            isLastMessageMine = isMine,
                            lastSenderName = senderName
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
                if (leftUserId != null && leftUserId != myUserId && mlsManager.hasGroup(convId)) {
                    handleMemberLeftRemoval(convId, leftUserId)
                }
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
                        if (mlsManager.hasGroup(convId)) {
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

                val currentEpoch = mlsManager.getRealMlsEpoch(conversationId)
                val nextEpoch = (currentEpoch + 1).toInt()

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
                    mlsManager.updateGroupEpoch(conversationId, nextEpoch.toLong())
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

    private suspend fun syncGroupCommitsQuietly(conversationId: String, justProcessedWelcome: Boolean = false) {
        try {
            val localEpoch = mlsManager.getGroupEpoch(conversationId)
            val commits = api.getPendingCommits(conversationId, localEpoch)

            if (commits.isNotEmpty()) {
                if (justProcessedWelcome) {
                    val realEpoch = mlsManager.getRealMlsEpoch(conversationId)
                    mlsManager.updateGroupEpoch(conversationId, realEpoch)
                    println("MemegramDebug [Welcome]: Синхронизирована metadata-эпоха с реальной MLS = $realEpoch")
                } else {
                    val newCommits = commits.filter { it.epoch > localEpoch }
                    if (newCommits.isNotEmpty()) {
                        newCommits.sortedBy { it.epoch }.forEach { commit ->
                            val success = try {
                                mlsManager.processCommit(conversationId, commit.commitDataB64)
                            } catch (e: Exception) {
                                println("MemegramDebug [BackgroundSync]: ❌ Ошибка коммита ${commit.epoch}: ${e.message}")
                                false
                            }
                            if (success) {
                                mlsManager.updateGroupEpoch(conversationId, commit.epoch)
                            }
                        }
                    }
                }
                mlsManager.flushState()
            }
        } catch (_: Exception) {}
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