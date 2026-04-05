package com.example.memegram

import kotlin.concurrent.Volatile

object ActiveChatCoordinator {
    @Volatile
    var conversationId: String? = null
}