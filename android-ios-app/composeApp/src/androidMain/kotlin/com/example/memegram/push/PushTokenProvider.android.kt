package com.example.memegram.push

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class FcmPushTokenProvider : PushTokenProvider {

    override suspend fun getToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (cont.isActive) cont.resume(token)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    override suspend fun deleteToken() = suspendCancellableCoroutine<Unit> { cont ->
        FirebaseMessaging.getInstance().deleteToken()
            .addOnSuccessListener {
                if (cont.isActive) cont.resume(Unit)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }
}

actual fun createPushTokenProvider(): PushTokenProvider = FcmPushTokenProvider()

actual fun currentPlatform(): String = "android"
