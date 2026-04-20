package com.example.memegram.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.memegram.ChatModel
import com.example.memegram.ChatStorageStat
import com.example.memegram.MediaItemInfo
import com.example.memegram.Message
import com.example.memegram.MessageStatus
import com.example.memegram.StorageTypeStat
import com.example.memegram.data.models.MarkAsReadRequest
import com.example.memegram.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import com.russhwolf.settings.Settings

class ChatRepositoryImpl(
    private val database: AppDatabase,
    private val settings: Settings
) : ChatRepository {

    private val chatQueries = database.appDatabaseQueries
    private val ioDispatcher = Dispatchers.IO

    override fun getAllChatsFlow(): Flow<List<ChatModel>> {
        return chatQueries.selectAllChats()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { entities ->
                entities.map { entity ->
                    ChatModel(
                        id = entity.conversationId.hashCode(),
                        conversationId = entity.conversationId,
                        name = entity.name,
                        lastMessage = entity.lastMessage,
                        timestamp = entity.timestamp,
                        unreadCount = entity.unreadCount.toInt(),
                        isLastMessageMine = entity.isLastMessageMine == 1L,
                        lastSenderName = entity.lastSenderName,
                        avatarMediaId = entity.avatarMediaId,
                        lastSenderAvatarMediaId = entity.lastSenderAvatarMediaId,
                        peerUserId = entity.peerUserId,
                        isGroup = entity.isGroup == 1L,
                        muteUntil = entity.muteUntil
                    )
                }
            }
    }

    override suspend fun saveChat(chat: ChatModel) {
        withContext(ioDispatcher) {
            chatQueries.insertOrUpdateChat(
                conversationId = chat.conversationId,
                name = chat.name,
                lastMessage = chat.lastMessage,
                timestamp = chat.timestamp,
                unreadCount = chat.unreadCount.toLong(),
                avatarMediaId = chat.avatarMediaId,
                isLastMessageMine = if (chat.isLastMessageMine) 1L else 0L,
                lastSenderName = chat.lastSenderName,
                lastSenderAvatarMediaId = chat.lastSenderAvatarMediaId,
                isGroup = if (chat.isGroup) 1L else 0L,
                peerUserId = chat.peerUserId,
                muteUntil = chat.muteUntil
            )
        }
    }

    override suspend fun saveChats(chats: List<ChatModel>) {
        withContext(ioDispatcher) {
            chatQueries.transaction {
                chats.forEach { chat ->
                    chatQueries.insertOrUpdateChat(
                        conversationId = chat.conversationId,
                        name = chat.name,
                        lastMessage = chat.lastMessage,
                        timestamp = chat.timestamp,
                        unreadCount = chat.unreadCount.toLong(),
                        avatarMediaId = chat.avatarMediaId,
                        isLastMessageMine = if (chat.isLastMessageMine) 1L else 0L,
                        lastSenderName = chat.lastSenderName,
                        lastSenderAvatarMediaId = chat.lastSenderAvatarMediaId,
                        isGroup = if (chat.isGroup) 1L else 0L,
                        peerUserId = chat.peerUserId,
                        muteUntil = chat.muteUntil
                    )
                }
            }
        }
    }

    override suspend fun deleteChat(conversationId: String) {
        withContext(ioDispatcher) {
            chatQueries.transaction {
                chatQueries.deleteMessagesByConversation(conversationId)
                chatQueries.deleteChat(conversationId)
            }
        }
    }

    override suspend fun deleteChats(conversationIds: List<String>) {
        if (conversationIds.isEmpty()) return
        withContext(ioDispatcher) {
            chatQueries.transaction {
                chatQueries.deleteMessagesByConversationIds(conversationIds)
                chatQueries.deleteChatsByIds(conversationIds)
            }
        }
    }

    override suspend fun setMuteUntil(conversationId: String, muteUntil: Long) {
        withContext(ioDispatcher) {
            chatQueries.updateChatMuteUntil(muteUntil, conversationId)
        }
    }

    override suspend fun setMuteUntilForIds(conversationIds: List<String>, muteUntil: Long) {
        if (conversationIds.isEmpty()) return
        withContext(ioDispatcher) {
            chatQueries.updateChatMuteUntilForIds(muteUntil, conversationIds)
        }
    }

    override suspend fun getChatById(conversationId: String): ChatModel? {
        return withContext(ioDispatcher) {
            val entity = chatQueries.selectAllChats().executeAsList()
                .find { it.conversationId == conversationId } ?: return@withContext null

            ChatModel(
                id = entity.conversationId.hashCode(),
                conversationId = entity.conversationId,
                name = entity.name,
                lastMessage = entity.lastMessage,
                timestamp = entity.timestamp,
                unreadCount = entity.unreadCount.toInt(),
                isLastMessageMine = entity.isLastMessageMine == 1L,
                lastSenderName = entity.lastSenderName,
                avatarMediaId = entity.avatarMediaId,
                lastSenderAvatarMediaId = entity.lastSenderAvatarMediaId,
                peerUserId = entity.peerUserId,
                isGroup = entity.isGroup == 1L,
                muteUntil = entity.muteUntil
            )
        }
    }

    override fun getMessagesFlow(conversationId: String): Flow<List<Message>> {
        val now = Clock.System.now().toEpochMilliseconds()
        chatQueries.updateMessageAccessStats(now, conversationId)

        return chatQueries.selectMessagesByConversation(conversationId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { entities ->
                entities.map { entity ->
                    Message(
                        id                 = entity.serverId.hashCode(),
                        serverId           = entity.serverId,
                        text               = entity.text,
                        isOutgoing         = entity.isOutgoing == 1L,
                        timestamp          = entity.timestamp,
                        status             = try { MessageStatus.valueOf(entity.status) }
                        catch (_: Exception) { MessageStatus.SENT },
                        type               = entity.type,
                        mediaId            = entity.mediaId,
                        encryptionMetadata = entity.encryptionMetadata,
                        localPreviewBytes  = entity.localPreviewBytes,
                        mediaUrl           = entity.mediaUrl,
                        senderUserId       = entity.senderUserId,
                        groupId            = entity.groupId,
                        originalText       = entity.originalText,
                        translatedText     = entity.translatedText,
                        translatedFromLang = entity.translatedFromLang,
                        isTranslated       = entity.isTranslated == 1L,
                        fileName           = entity.fileName,
                        fileSize           = entity.fileSize,
                        fileMime           = entity.fileMime,
                        localFilePath      = entity.localFilePath,
                        nsfwFlag           = entity.nsfwFlag?.let { it == 1L }
                    )

                }
            }
    }

    override suspend fun saveMessage(message: Message, conversationId: String) {
        withContext(ioDispatcher) {
            val realId = message.serverId.takeIf { it.isNotBlank() } ?: "temp_${message.id}"
            val now = Clock.System.now().toEpochMilliseconds()

            chatQueries.transaction {
                if (message.serverId.isNotBlank()) {
                    chatQueries.deleteMessageByServerId("temp_${message.id}")
                }

                chatQueries.insertOrIgnoreMessage(
                    realId, conversationId, message.text,
                    if (message.isOutgoing) 1L else 0L, message.timestamp, message.status.name,
                    message.type, message.mediaId, message.encryptionMetadata,
                    message.localPreviewBytes, message.mediaUrl, now, message.senderUserId,
                    message.groupId,
                    message.originalText, message.translatedText, message.translatedFromLang,
                    if (message.isTranslated) 1L else 0L,
                    message.fileName, message.fileSize, message.fileMime, message.localFilePath,
                    message.nsfwFlag?.let { if (it) 1L else 0L }
                )
                chatQueries.updateExistingMessage(
                    message.text, message.status.name,
                    message.type, message.mediaId, message.encryptionMetadata,
                    message.localPreviewBytes, message.mediaUrl, now, message.timestamp,
                    message.senderUserId, message.groupId,
                    message.fileName, message.fileSize, message.fileMime, message.localFilePath,
                    realId
                )
                runGarbageCollector(conversationId)
            }
        }
    }


    override suspend fun saveMessages(messages: List<Message>, conversationId: String) {
        withContext(ioDispatcher) {
            val now = Clock.System.now().toEpochMilliseconds()

            chatQueries.transaction {
                messages.forEach { message ->
                    val realId = message.serverId.takeIf { it.isNotBlank() } ?: "temp_${message.id}"
                    chatQueries.insertMessage(
                        serverId           = realId,
                        conversationId     = conversationId,
                        text               = message.text,
                        isOutgoing         = if (message.isOutgoing) 1L else 0L,
                        timestamp          = message.timestamp,
                        status             = message.status.name,
                        type               = message.type,
                        mediaId            = message.mediaId,
                        encryptionMetadata = message.encryptionMetadata,
                        localPreviewBytes  = message.localPreviewBytes,
                        mediaUrl           = message.mediaUrl,
                        lastAccessedAt     = now,
                        accessCount        = 1L,
                        senderUserId       = message.senderUserId,
                        groupId            = message.groupId,
                        originalText       = message.originalText,
                        translatedText     = message.translatedText,
                        translatedFromLang = message.translatedFromLang,
                        isTranslated       = if (message.isTranslated) 1L else 0L,
                        fileName           = message.fileName,
                        fileSize           = message.fileSize,
                        fileMime           = message.fileMime,
                        localFilePath      = message.localFilePath,
                        nsfwFlag           = message.nsfwFlag?.let { if (it) 1L else 0L }
                    )
                    chatQueries.updateExistingMessage(
                        message.text, message.status.name,
                        message.type, message.mediaId, message.encryptionMetadata,
                        message.localPreviewBytes, message.mediaUrl, now, message.timestamp,
                        message.senderUserId, message.groupId,
                        message.fileName, message.fileSize, message.fileMime, message.localFilePath,
                        realId
                    )
                }
                runGarbageCollector(conversationId)
            }
        }
    }

    override suspend fun deleteMessages(conversationId: String) {
        withContext(ioDispatcher) {
            chatQueries.deleteMessagesByConversation(conversationId)
        }
    }

    override suspend fun deleteMessageByServerId(serverId: String) {
        withContext(ioDispatcher) {
            chatQueries.deleteMessageByServerId(serverId)
        }
    }

    override suspend fun updateMessageLocalFile(
        serverId: String,
        localFilePath: String,
        previewBytes: ByteArray?
    ) {
        withContext(ioDispatcher) {
            chatQueries.updateMessageLocalFile(localFilePath, previewBytes, serverId)
        }
    }

    override suspend fun updateMessageLocalPreview(
        serverId: String,
        previewBytes: ByteArray
    ) {
        withContext(ioDispatcher) {
            chatQueries.updateMessageLocalPreview(previewBytes, serverId)
        }
    }

    override suspend fun updateMessageNsfwFlag(serverId: String, flag: Boolean) {
        withContext(ioDispatcher) {
            chatQueries.updateMessageNsfwFlag(if (flag) 1L else 0L, serverId)
        }
    }

    override suspend fun clearAllLocalData() {
        withContext(ioDispatcher) {
            chatQueries.transaction {
                chatQueries.deleteAllMessages()
                chatQueries.deleteAllChats()
            }
            chatQueries.vacuumDb()
        }
    }

    override suspend fun getMessagesOnce(conversationId: String): List<Message> {
        return withContext(ioDispatcher) {
            chatQueries.selectMessagesByConversation(conversationId)
                .executeAsList()
                .map { entity ->
                    Message(
                        id                 = entity.serverId.hashCode(),
                        serverId           = entity.serverId,
                        text               = entity.text,
                        isOutgoing         = entity.isOutgoing == 1L,
                        timestamp          = entity.timestamp,
                        status             = try { MessageStatus.valueOf(entity.status) }
                        catch (_: Exception) { MessageStatus.SENT },
                        type               = entity.type,
                        mediaId            = entity.mediaId,
                        encryptionMetadata = entity.encryptionMetadata,
                        localPreviewBytes  = entity.localPreviewBytes,
                        mediaUrl           = entity.mediaUrl,
                        senderUserId       = entity.senderUserId,
                        groupId            = entity.groupId,
                        originalText       = entity.originalText,
                        translatedText     = entity.translatedText,
                        translatedFromLang = entity.translatedFromLang,
                        isTranslated       = entity.isTranslated == 1L,
                        fileName           = entity.fileName,
                        fileSize           = entity.fileSize,
                        fileMime           = entity.fileMime,
                        localFilePath      = entity.localFilePath,
                        nsfwFlag           = entity.nsfwFlag?.let { it == 1L }
                    )
                }
        }
    }

    override suspend fun getLastMessageText(conversationId: String): String? {
        return withContext(ioDispatcher) {
            val messages = chatQueries.selectMessagesByConversation(conversationId).executeAsList()
            messages.lastOrNull()?.text
        }
    }

    // ── Translation ──────────────────────────────────────────────────

    override suspend fun updateMessageTranslation(
        serverId: String,
        translatedText: String,
        fromLang: String
    ) {
        withContext(ioDispatcher) {
            chatQueries.updateMessageTranslation(translatedText, translatedText, fromLang, serverId)
        }
    }

    override suspend fun revertMessageTranslation(serverId: String) {
        withContext(ioDispatcher) {
            chatQueries.revertMessageTranslation(serverId)
        }
    }

    override suspend fun showCachedTranslation(serverId: String) {
        withContext(ioDispatcher) {
            chatQueries.showCachedTranslation(serverId)
        }
    }

    // ── Voice transcription ─────────────────────────────────────────

    override suspend fun updateVoiceTranscription(
        serverId: String,
        transcribedText: String,
        translatedText: String?,
        detectedLang: String
    ) {
        withContext(ioDispatcher) {
            chatQueries.updateVoiceTranscription(
                transcribedText,
                translatedText,
                detectedLang,
                serverId
            )
        }
    }

    override suspend fun hideVoiceTranscription(serverId: String) {
        withContext(ioDispatcher) {
            chatQueries.hideVoiceTranscription(serverId)
        }
    }

    override suspend fun showVoiceTranscription(serverId: String) {
        withContext(ioDispatcher) {
            chatQueries.showVoiceTranscription(serverId)
        }
    }

    // ── Storage analytics ────────────────────────────────────────────

    override suspend fun getStorageByType(): List<StorageTypeStat> {
        return withContext(ioDispatcher) {
            chatQueries.storageByType().executeAsList().map {
                StorageTypeStat(
                    type = it.type,
                    messageCount = it.messageCount,
                    totalSize = it.totalSize
                )
            }
        }
    }

    override suspend fun getStoragePerConversationPerType(): List<ChatStorageStat> {
        return withContext(ioDispatcher) {
            chatQueries.storagePerConversationPerType().executeAsList().map {
                ChatStorageStat(
                    conversationId = it.conversationId,
                    chatName = it.chatName ?: it.conversationId,
                    avatarMediaId = it.avatarMediaId,
                    type = it.type,
                    messageCount = it.messageCount,
                    typeSize = it.typeSize
                )
            }
        }
    }

    override suspend fun getStorageByConversationAndType(conversationId: String): List<StorageTypeStat> {
        return withContext(ioDispatcher) {
            chatQueries.storageByConversationAndType(conversationId).executeAsList().map {
                StorageTypeStat(
                    type = it.type,
                    messageCount = it.messageCount,
                    totalSize = it.totalSize
                )
            }
        }
    }

    override suspend fun getTotalStorageSize(): Pair<Long, Long> {
        return withContext(ioDispatcher) {
            val result = chatQueries.totalStorageSize().executeAsOne()
            Pair(result.messageCount, result.totalSize)
        }
    }

    override suspend fun deleteMessagesByType(type: String) {
        withContext(ioDispatcher) {
            chatQueries.deleteMessagesByType(type)
        }
    }

    override suspend fun deleteMessagesByConversationAndType(conversationId: String, type: String) {
        withContext(ioDispatcher) {
            chatQueries.deleteMessagesByConversationAndType(conversationId, type)
        }
    }

    override suspend fun getMediaItemsByConversation(conversationId: String): List<MediaItemInfo> {
        return withContext(ioDispatcher) {
            chatQueries.selectMediaItemsByConversation(conversationId).executeAsList().map {
                MediaItemInfo(
                    serverId = it.serverId,
                    type = it.type,
                    mediaId = it.mediaId,
                    previewBytes = it.localPreviewBytes,
                    estimatedSize = it.estimatedSize,
                    timestamp = it.timestamp
                )
            }
        }
    }

    override suspend fun deleteOldPrivateChatMedia(thresholdMs: Long) {
        withContext(ioDispatcher) {
            chatQueries.deleteOldPrivateChatMedia(thresholdMs)
        }
    }

    override suspend fun deleteOldGroupChatMedia(thresholdMs: Long) {
        withContext(ioDispatcher) {
            chatQueries.deleteOldGroupChatMedia(thresholdMs)
        }
    }

    private fun runGarbageCollector(conversationId: String) {
        val strategy = settings.getString("cache_cleanup_strategy", "FIFO")
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

        when (strategy) {
            "FIFO" -> {
                val limit = settings.getLong("fifo_keep_limit", 1000L)
                chatQueries.deleteOldMessages_FIFO(conversationId, conversationId, limit)
            }
            "TTL" -> {
                val ttlMs = settings.getLong("ttl_days", 30L) * 86_400_000L
                chatQueries.deleteOldMessages_TTL(
                    timestamp = now - ttlMs
                )
            }
            "LRU" -> {
                val limit = settings.getLong("lru_global_limit", 5000L)
                chatQueries.deleteOldMessages_LRU(
                    value = limit
                )
            }
            "LFU" -> {
                val limit = settings.getLong("lfu_global_limit", 5000L)
                chatQueries.deleteOldMessages_LFU(
                    value = limit
                )
            }
        }
    }
}