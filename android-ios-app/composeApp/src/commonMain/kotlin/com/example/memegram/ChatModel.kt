package com.example.memegram

data class ChatModel(
    val id: Int,
    val name: String,
    val lastMessage: String,
    val timestamp: Long = 0L,
    val unreadCount: Int = 0
)