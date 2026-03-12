package com.example.memegram

data class ChatModel(
    val id: Int,
    val name: String,
    val lastMessage: String,
    val avatarResId: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0
)
