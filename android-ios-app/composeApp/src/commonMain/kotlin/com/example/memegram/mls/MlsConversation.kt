package com.example.memegram.mls

data class MlsConversation(
    val conversationId: String,
    val epoch: Long = 0,
    val memberCount: Int = 0
)