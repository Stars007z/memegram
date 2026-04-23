package com.example.memegram.push

/**
 * Bridge object populated from Swift side after Firebase initializes
 * and reports an FCM token via FIRMessagingDelegate.
 *
 * Swift code calls [setToken] when token is received/refreshed,
 * and [clearToken] after deleting it.
 */
import kotlin.concurrent.AtomicReference

/**
 * Bridge object populated from Swift side after Firebase initializes
 * and reports an FCM token via FIRMessagingDelegate.
 *
 * Swift code calls [setToken] when token is received/refreshed,
 * and [clearToken] after deleting it.
 */
object IosPushTokenBridge {
    private val tokenRef = AtomicReference<String?>(null)

    val currentToken: String?
        get() = tokenRef.value

    fun setToken(token: String?) {
        tokenRef.value = token
    }

    fun clearToken() {
        tokenRef.value = null
    }
}

private class IosFcmPushTokenProvider : PushTokenProvider {
    override suspend fun getToken(): String? {
        repeat(30) {
            val t = IosPushTokenBridge.currentToken
            if (!t.isNullOrBlank()) return t
            kotlinx.coroutines.delay(100)
        }
        return IosPushTokenBridge.currentToken
    }

    override suspend fun deleteToken() {
        IosPushTokenBridge.clearToken()
    }
}

actual fun createPushTokenProvider(): PushTokenProvider = IosFcmPushTokenProvider()

actual fun currentPlatform(): String = "ios"
