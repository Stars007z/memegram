package com.example.memegram.push

interface PushTokenProvider {
    suspend fun getToken(): String?

    suspend fun deleteToken()
}

expect fun createPushTokenProvider(): PushTokenProvider
expect fun currentPlatform(): String
