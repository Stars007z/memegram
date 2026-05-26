package com.example.memegram.conversation

import com.example.memegram.ActiveChatCoordinator
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager

object ConversationLocalCleaner {
    suspend fun purge(
        conversationId: String,
        chatRepository: ChatRepository,
        mlsManager: MlsManager?,
    ) {
        if (conversationId.isBlank()) return

        runCatching { chatRepository.deleteMessages(conversationId) }
        runCatching { chatRepository.deleteChat(conversationId) }
        if (mlsManager != null) {
            runCatching { mlsManager.deleteLocalGroup(conversationId) }
            runCatching { mlsManager.flushState() }
        }
        if (ActiveChatCoordinator.conversationId == conversationId) {
            ActiveChatCoordinator.conversationId = null
        }
    }
}
