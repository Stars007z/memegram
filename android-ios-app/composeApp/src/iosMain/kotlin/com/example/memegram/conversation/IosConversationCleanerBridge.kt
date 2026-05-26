package com.example.memegram.conversation

import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

object IosConversationCleanerBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun purge(conversationId: String) {
        if (conversationId.isBlank()) return
        scope.launch {
            runCatching {
                val koin = KoinPlatform.getKoin()
                val chatRepository = koin.get<ChatRepository>()
                val mlsManager = runCatching { koin.get<MlsManager>() }.getOrNull()
                ConversationLocalCleaner.purge(conversationId, chatRepository, mlsManager)
                println("MemegramDebug [iOS push] purged local state for conv=$conversationId")
            }.onFailure {
                println("MemegramDebug [iOS push] purge failed: ${it.message}")
            }
        }
    }
}
