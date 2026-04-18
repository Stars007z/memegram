package com.example.memegram.push

private class IosPushTokenProviderStub : PushTokenProvider {
    override suspend fun getToken(): String? = null
    override suspend fun deleteToken() = Unit
}

actual fun createPushTokenProvider(): PushTokenProvider = IosPushTokenProviderStub()

actual fun currentPlatform(): String = "ios"
