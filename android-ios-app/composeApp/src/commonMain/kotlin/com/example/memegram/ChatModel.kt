package com.example.memegram

data class ChatModel(
    val id: Int,
    val conversationId: String = "",
    val name: String,
    val lastMessage: String,
    val timestamp: Long,
    val unreadCount: Int = 0
)

data class Message(
    val id: Int,
    val serverId: String = "",
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: Long = 0L,
    val status: MessageStatus = MessageStatus.SENT
)

enum class MessageStatus { SENDING, SENT, FAILED }