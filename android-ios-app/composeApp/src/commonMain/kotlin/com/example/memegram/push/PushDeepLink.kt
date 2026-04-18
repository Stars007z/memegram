package com.example.memegram.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PendingPushNavigation(
    val conversationId: String,
    val chatName: String,
    val avatarMediaId: String,
)


object PushDeepLink {
    private val _pending = MutableStateFlow<PendingPushNavigation?>(null)
    val pending: StateFlow<PendingPushNavigation?> get() = _pending

    fun set(target: PendingPushNavigation) {
        _pending.value = target
    }

    fun consume(): PendingPushNavigation? {
        val current = _pending.value
        if (current != null) _pending.value = null
        return current
    }
}
