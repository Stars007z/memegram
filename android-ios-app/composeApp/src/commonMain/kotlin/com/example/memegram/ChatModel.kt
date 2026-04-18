package com.example.memegram

data class ChatModel(
    val id: Int,
    val conversationId: String = "",
    val name: String,
    val lastMessage: String,
    val timestamp: Long,
    val unreadCount: Int = 0,
    val isLastMessageMine: Boolean = false,
    val lastSenderName: String? = null,
    val avatarMediaId: String? = null,
    val lastSenderAvatarMediaId: String? = null,
    val peerUserId: String? = null,
    val isGroup: Boolean = false,
    val muteUntil: Long = 0L
)

data class Message(
    val id: Int,
    val serverId: String = "",
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: Long = 0L,
    val status: MessageStatus = MessageStatus.SENT,
    val type: String = "text",
    val mediaId: String? = null,
    val encryptionMetadata: String? = null,
    val localPreviewBytes: ByteArray? = null,
    val mediaUrl: String? = null,
    val senderUserId: String? = null,
    val groupId: String? = null,
    val originalText: String? = null,
    val translatedText: String? = null,
    val translatedFromLang: String? = null,
    val isTranslated: Boolean = false,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val fileMime: String? = null,
    val localFilePath: String? = null
)

enum class MessageStatus { SENDING, SENT, FAILED }

// ── Storage analytics models ─────────────────────────────────────────

data class StorageTypeStat(
    val type: String,
    val messageCount: Long,
    val totalSize: Long
)

data class ChatStorageStat(
    val conversationId: String,
    val chatName: String,
    val avatarMediaId: String?,
    val type: String,
    val messageCount: Long,
    val typeSize: Long
)

data class MediaItemInfo(
    val serverId: String,
    val type: String,
    val mediaId: String?,
    val previewBytes: ByteArray?,
    val estimatedSize: Long,
    val timestamp: Long
)