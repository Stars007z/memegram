package com.example.memegram

import com.russhwolf.settings.Settings

internal object DeletedPeerStore {
    private const val CONVERSATION_PREFIX = "deleted_peer_conversation_"
    private const val CONVERSATION_PEER_PREFIX = "deleted_peer_conversation_peer_"
    private const val USER_PREFIX = "deleted_peer_user_"

    fun markConversationDeleted(settings: Settings, conversationId: String, peerUserId: String? = null) {
        if (conversationId.isBlank()) return
        settings.putBoolean(CONVERSATION_PREFIX + conversationId, true)
        peerUserId?.takeIf { it.isNotBlank() }?.let { peerId ->
            settings.putString(CONVERSATION_PEER_PREFIX + conversationId, peerId)
            markUserDeleted(settings, peerId)
        }
    }

    fun markUserDeleted(settings: Settings, userId: String) {
        if (userId.isBlank()) return
        settings.putBoolean(USER_PREFIX + userId, true)
    }

    fun isConversationDeleted(settings: Settings, conversationId: String): Boolean =
        conversationId.isNotBlank() && settings.getBoolean(CONVERSATION_PREFIX + conversationId, false)

    fun conversationPeerId(settings: Settings, conversationId: String): String? =
        if (conversationId.isBlank()) null
        else settings.getStringOrNull(CONVERSATION_PEER_PREFIX + conversationId)?.takeIf { it.isNotBlank() }

    fun isUserDeleted(settings: Settings, userId: String?): Boolean =
        !userId.isNullOrBlank() && settings.getBoolean(USER_PREFIX + userId, false)

    fun isDeleted(settings: Settings, conversationId: String, userId: String?): Boolean =
        isConversationDeleted(settings, conversationId) || isUserDeleted(settings, userId)
}
