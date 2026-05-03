package com.example.memegram.data.repository

import com.example.memegram.ChatModel
import com.example.memegram.ChatStorageStat
import com.example.memegram.MediaItemInfo
import com.example.memegram.Message
import com.example.memegram.StoredChatMessage
import com.example.memegram.StorageTypeStat
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getAllChatsFlow(): Flow<List<ChatModel>>
    suspend fun saveChat(chat: ChatModel)
    suspend fun saveChats(chats: List<ChatModel>)
    suspend fun deleteChat(conversationId: String)
    suspend fun deleteChats(conversationIds: List<String>)
    suspend fun getChatById(conversationId: String): ChatModel?

    suspend fun setMuteUntil(conversationId: String, muteUntil: Long)
    suspend fun setMuteUntilForIds(conversationIds: List<String>, muteUntil: Long)

    fun getMessagesFlow(conversationId: String): Flow<List<Message>>
    fun getAllMessagesFlow(): Flow<List<StoredChatMessage>>
    suspend fun saveMessage(message: Message, conversationId: String)
    suspend fun saveMessages(messages: List<Message>, conversationId: String)
    suspend fun deleteMessages(conversationId: String)
    suspend fun deleteMessageByServerId(serverId: String)
    suspend fun updateMessageLocalFile(serverId: String, localFilePath: String, previewBytes: ByteArray?)
    suspend fun updateMessageLocalPreview(serverId: String, previewBytes: ByteArray)
    suspend fun markOutgoingMessagesRead(conversationId: String, lastReadServerId: String)

    suspend fun clearAllLocalData()

    suspend fun getLastMessageText(conversationId: String): String?

    suspend fun getMessagesOnce(conversationId: String): List<Message>

    // ── Translation ──────────────────────────────────────────────────
    suspend fun updateMessageTranslation(serverId: String, translatedText: String, fromLang: String)
    suspend fun revertMessageTranslation(serverId: String)
    suspend fun showCachedTranslation(serverId: String)

    // ── Storage analytics ────────────────────────────────────────────
    suspend fun getStorageByType(): List<StorageTypeStat>
    suspend fun getStoragePerConversationPerType(): List<ChatStorageStat>
    suspend fun getStorageByConversationAndType(conversationId: String): List<StorageTypeStat>
    suspend fun getTotalStorageSize(): Pair<Long, Long>
    suspend fun deleteMessagesByType(type: String)
    suspend fun deleteMessagesByConversationAndType(conversationId: String, type: String)
    suspend fun getMediaItemsByConversation(conversationId: String): List<MediaItemInfo>
    suspend fun deleteOldPrivateChatMedia(thresholdMs: Long)
    suspend fun deleteOldGroupChatMedia(thresholdMs: Long)
}
