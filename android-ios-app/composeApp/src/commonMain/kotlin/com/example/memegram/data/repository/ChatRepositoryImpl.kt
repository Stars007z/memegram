package com.example.memegram.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.memegram.ChatModel
import com.example.memegram.Message
import com.example.memegram.MessageStatus
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
                        unreadCount = entity.unreadCount.toInt()
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
                avatarMediaId = null
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
                        avatarMediaId = null
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
                unreadCount = entity.unreadCount.toInt()
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
                        mediaUrl           = entity.mediaUrl
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
                    message.localPreviewBytes, message.mediaUrl, now
                )
                chatQueries.updateExistingMessage(
                    message.text, message.status.name,
                    message.type, message.mediaId, message.encryptionMetadata,
                    message.localPreviewBytes, message.mediaUrl, now, message.timestamp, realId
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
                        accessCount        = 1L
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
                        mediaUrl           = entity.mediaUrl
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