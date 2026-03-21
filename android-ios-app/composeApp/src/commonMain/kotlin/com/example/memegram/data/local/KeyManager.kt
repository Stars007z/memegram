package com.example.memegram.data.local

import com.ionspin.kotlin.crypto.signature.Signature
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface KeyManager {
    fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray>
    fun signChallenge(challengeBase64: String): ByteArray
    fun getPublicKeyBase64(): String
    fun hasKeyPair(): Boolean
}

@OptIn(ExperimentalEncodingApi::class)
class CommonKeyManager(private val settings: Settings) : KeyManager {

    private val KEY_PRIVATE = "identity_private_key"
    private val KEY_PUBLIC  = "identity_public_key"

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray> {
        val existingPriv = settings.getStringOrNull(KEY_PRIVATE)
        val existingPub  = settings.getStringOrNull(KEY_PUBLIC)
        if (existingPriv != null && existingPub != null) {
            return Pair(
                Base64.decode(existingPriv),
                Base64.decode(existingPub)
            )
        }
        val keyPair = Signature.keypair()
        val privBytes = keyPair.secretKey.asByteArray()
        val pubBytes  = keyPair.publicKey.asByteArray()

        settings.putString(KEY_PRIVATE, Base64.encode(privBytes))
        settings.putString(KEY_PUBLIC,  Base64.encode(pubBytes))
        return Pair(privBytes, pubBytes)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun signChallenge(challengeBase64: String): ByteArray {
        val privBytes = Base64.decode(
            settings.getStringOrNull(KEY_PRIVATE) ?: error("No private key")
        )
        val msgBytes  = Base64.decode(challengeBase64)

        return Signature.detached(
            message   = msgBytes.asUByteArray(),
            secretKey = privBytes.asUByteArray()
        ).asByteArray()
    }

    override fun getPublicKeyBase64(): String {
        val (_, pub) = getOrCreateKeyPair()
        return Base64.encode(pub)
    }

    override fun hasKeyPair(): Boolean =
        settings.getStringOrNull(KEY_PRIVATE) != null
}

expect fun createPlatformKeyManager(settings: Settings): KeyManager