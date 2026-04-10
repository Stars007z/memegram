@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
package com.example.memegram.data.local

import com.ionspin.kotlin.crypto.signature.Signature
import com.russhwolf.settings.Settings
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

actual fun createPlatformKeyManager(settings: Settings): KeyManager = IOSKeyManager()

@OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
class IOSKeyManager : KeyManager {
    private val KEY_PRIVATE = "identity_private_key"
    private val KEY_PUBLIC = "identity_public_key"
    private val SERVICE = "com.example.memegram.keys"

    private fun saveToKeychain(account: String, value: String) {
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return

        val deleteQuery = mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to account
        )
        val deleteRef = CFBridgingRetain(deleteQuery) as CFDictionaryRef?
        SecItemDelete(deleteRef)
        CFBridgingRelease(deleteRef)

        val insertQuery = mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to account,
            kSecValueData to data,
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        )
        val insertRef = CFBridgingRetain(insertQuery) as CFDictionaryRef?
        SecItemAdd(insertRef, null)
        CFBridgingRelease(insertRef)
    }

    private fun getFromKeychain(account: String): String? {
        val query = mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to account,
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne
        )

        val queryRef = CFBridgingRetain(query) as CFDictionaryRef?
        var resultString: String? = null

        memScoped {
            val resultPtr = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(queryRef, resultPtr.ptr)

            if (status == errSecSuccess) {
                val dataRef = resultPtr.value
                if (dataRef != null) {
                    val nsData = CFBridgingRelease(dataRef) as NSData
                    resultString = NSString.create(data = nsData, encoding = NSUTF8StringEncoding)?.toString()
                }
            }
        }
        CFBridgingRelease(queryRef)
        return resultString
    }

    override fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray> {
        val existingPriv = getFromKeychain(KEY_PRIVATE)
        val existingPub = getFromKeychain(KEY_PUBLIC)

        if (existingPriv != null && existingPub != null) {
            return Pair(Base64.decode(existingPriv), Base64.decode(existingPub))
        }

        val keyPair = Signature.keypair()
        val privBytes = keyPair.secretKey.asByteArray()
        val pubBytes = keyPair.publicKey.asByteArray()

        saveToKeychain(KEY_PRIVATE, Base64.encode(privBytes))
        saveToKeychain(KEY_PUBLIC, Base64.encode(pubBytes))

        return Pair(privBytes, pubBytes)
    }

    override fun signChallenge(challengeBase64: String): ByteArray {
        val privBase64 = getFromKeychain(KEY_PRIVATE) ?: error("No private key found in Keychain")
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

    override fun hasKeyPair(): Boolean = getFromKeychain(KEY_PRIVATE) != null
}