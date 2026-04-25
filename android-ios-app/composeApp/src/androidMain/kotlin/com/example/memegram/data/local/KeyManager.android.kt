package com.example.memegram.data.local

import com.example.memegram.AppContextHolder
import com.ionspin.kotlin.crypto.signature.Signature
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.core.content.edit

actual fun createPlatformKeyManager(settings: Settings): KeyManager = AndroidKeyManager()

@OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
class AndroidKeyManager : KeyManager {
    private val KEY_PRIVATE = "identity_private_key"
    private val KEY_PUBLIC = "identity_public_key"

    private val securePrefs by lazy {
        SecurePrefsFactory.create(AppContextHolder.context, "key_manager_secure_prefs")
    }

    override fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray> {
        val existingPriv = securePrefs.getString(KEY_PRIVATE, null)
        val existingPub = securePrefs.getString(KEY_PUBLIC, null)

        if (existingPriv != null && existingPub != null) {
            return Pair(Base64.decode(existingPriv), Base64.decode(existingPub))
        }

        val keyPair = Signature.keypair()
        val privBytes = keyPair.secretKey.asByteArray()
        val pubBytes = keyPair.publicKey.asByteArray()

        securePrefs.edit {
            putString(KEY_PRIVATE, Base64.encode(privBytes))
            putString(KEY_PUBLIC, Base64.encode(pubBytes))
        }
        return Pair(privBytes, pubBytes)
    }

    override fun signChallenge(challengeBase64: String): ByteArray {
        val privBase64 = securePrefs.getString(KEY_PRIVATE, null) ?: error("No private key found in Keystore")
        val privBytes = Base64.decode(privBase64)
        val msgBytes = Base64.decode(challengeBase64)

        return Signature.detached(
            message = msgBytes.asUByteArray(),
            secretKey = privBytes.asUByteArray()
        ).asByteArray()
    }

    override fun getPublicKeyBase64(): String {
        val (_, pub) = getOrCreateKeyPair()
        return Base64.encode(pub)
    }

    override fun hasKeyPair(): Boolean = securePrefs.getString(KEY_PRIVATE, null) != null
}