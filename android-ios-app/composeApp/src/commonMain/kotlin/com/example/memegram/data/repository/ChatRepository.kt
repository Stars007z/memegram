package com.example.memegram.data.repository

import com.example.memegram.ChatModel
import com.example.memegram.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getAllChatsFlow(): Flow<List<ChatModel>>
    suspend fun saveChat(chat: ChatModel)
    suspend fun saveChats(chats: List<ChatModel>)
    suspend fun deleteChat(conversationId: String)
    suspend fun getChatById(conversationId: String): ChatModel?

    fun getMessagesFlow(conversationId: String): Flow<List<Message>>
    suspend fun saveMessage(message: Message, conversationId: String)
    suspend fun saveMessages(messages: List<Message>, conversationId: String)
    suspend fun deleteMessages(conversationId: String)
    suspend fun deleteMessageByServerId(serverId: String)

    suspend fun clearAllLocalData()

    suspend fun getLastMessageText(conversationId: String): String?

    suspend fun getMessagesOnce(conversationId: String): List<Message>
}