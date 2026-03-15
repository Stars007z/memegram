package com.example.memegram

data class Message(
    val id: Int,
    val text: String,
    val imageUri: String? = null,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean = false,
    val showDateSeparator: Boolean = false,
    val dateSeparatorText: String = ""
)
