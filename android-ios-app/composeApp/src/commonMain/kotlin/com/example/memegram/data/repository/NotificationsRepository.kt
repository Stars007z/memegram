package com.example.memegram.data.repository

import com.example.memegram.data.models.RegisterPushTokenRequest
import com.example.memegram.data.network.ApiService
import com.example.memegram.push.PushTokenProvider
import com.example.memegram.push.currentPlatform

interface NotificationsRepository {
    suspend fun registerCurrentDeviceToken(): Result<Unit>
}

class NotificationsRepositoryImpl(
    private val api: ApiService,
    private val pushTokenProvider: PushTokenProvider
) : NotificationsRepository {

    override suspend fun registerCurrentDeviceToken(): Result<Unit> = runCatching {
        val token = pushTokenProvider.getToken()
        if (token.isNullOrBlank()) {
            println("MemegramDebug [Push] No token from platform — skip register")
            return@runCatching
        }
        api.registerPushToken(
            RegisterPushTokenRequest(
                platform = currentPlatform(),
                pushToken = token
            )
        )
        println("MemegramDebug [Push] Token registered on backend")
    }.onFailure { e ->
        println("MemegramDebug [Push] registerCurrentDeviceToken failed: ${e.message}")
    }
}
