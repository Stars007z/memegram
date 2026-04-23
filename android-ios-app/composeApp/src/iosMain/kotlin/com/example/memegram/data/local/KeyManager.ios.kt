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

    /**
     * Builds a CFDictionary by adding entries one-by-one to a CFMutableDictionary.
     * Caller is responsible for releasing the returned ref.
     */
    private fun buildQuery(entries: List<Pair<CFStringRef?, CFTypeRef?>>): CFMutableDictionaryRef {
        val dict = CFDictionaryCreateMutable(
            null, entries.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        )!!
        for ((k, v) in entries) {
            CFDictionarySetValue(dict, k, v)
        }
        return dict
    }

    private fun saveToKeychain(account: String, value: String): Boolean {
        val nsValue = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        if (nsValue == null) {
            println("MemegramDebug [KeyManager.ios] saveToKeychain($account): UTF8 encode failed")
            return false
        }
        val accountCF = CFBridgingRetain(account) as CFStringRef?
        val serviceCF = CFBridgingRetain(SERVICE) as CFStringRef?
        val dataCF = CFBridgingRetain(nsValue) as CFDataRef?
        try {
            val deleteQuery = buildQuery(listOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceCF,
                kSecAttrAccount to accountCF,
            ))
            val deleteStatus = SecItemDelete(deleteQuery)
            CFRelease(deleteQuery)
            println("MemegramDebug [KeyManager.ios] SecItemDelete($account) status=$deleteStatus")

            val insertQuery = buildQuery(listOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceCF,
                kSecAttrAccount to accountCF,
                kSecValueData to dataCF,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
            ))
            val addStatus = SecItemAdd(insertQuery, null)
            CFRelease(insertQuery)
            println("MemegramDebug [KeyManager.ios] SecItemAdd($account) status=$addStatus")
            return addStatus == errSecSuccess
        } finally {
            CFBridgingRelease(accountCF)
            CFBridgingRelease(serviceCF)
            CFBridgingRelease(dataCF)
        }
    }

    private fun getFromKeychain(account: String): String? {
        val accountCF = CFBridgingRetain(account) as CFStringRef?
        val serviceCF = CFBridgingRetain(SERVICE) as CFStringRef?
        try {
            val query = buildQuery(listOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceCF,
                kSecAttrAccount to accountCF,
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ))
            return memScoped {
                val resultPtr = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, resultPtr.ptr)
                CFRelease(query)
                if (status != errSecSuccess) {
                    if (status != errSecItemNotFound) {
                        println("MemegramDebug [KeyManager.ios] SecItemCopyMatching($account) status=$status")
                    }
                    return@memScoped null
                }
                val dataRef = resultPtr.value ?: return@memScoped null
                val nsData = CFBridgingRelease(dataRef) as NSData
                NSString.create(data = nsData, encoding = NSUTF8StringEncoding)?.toString()
            }
        } finally {
            CFBridgingRelease(accountCF)
            CFBridgingRelease(serviceCF)
        }
    }

    override fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray> {
        val existingPriv = getFromKeychain(KEY_PRIVATE)
        val existingPub = getFromKeychain(KEY_PUBLIC)

        if (existingPriv != null && existingPub != null) {
            println("MemegramDebug [KeyManager.ios] getOrCreateKeyPair: loaded existing pair from Keychain")
            return Pair(Base64.decode(existingPriv), Base64.decode(existingPub))
        }

        println("MemegramDebug [KeyManager.ios] getOrCreateKeyPair: generating new (existingPriv=${existingPriv != null}, existingPub=${existingPub != null})")
        val keyPair = Signature.keypair()
        val privBytes = keyPair.secretKey.asByteArray()
        val pubBytes = keyPair.publicKey.asByteArray()

        val savedPriv = saveToKeychain(KEY_PRIVATE, Base64.encode(privBytes))
        val savedPub = saveToKeychain(KEY_PUBLIC, Base64.encode(pubBytes))
        println("MemegramDebug [KeyManager.ios] getOrCreateKeyPair: saved priv=$savedPriv pub=$savedPub")

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

    override fun hasKeyPair(): Boolean {
        val has = getFromKeychain(KEY_PRIVATE) != null
        println("MemegramDebug [KeyManager.ios] hasKeyPair=$has")
        return has
    }
}
