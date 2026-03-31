@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.example.memegram.data.local

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

actual fun getDatabasePassphrase(): String {
    val account = "sql_cipher_passphrase"
    val service = "com.example.memegram.database"

    val query = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
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
                val nsString = NSString.create(data = nsData, encoding = NSUTF8StringEncoding)
                resultString = nsString?.toString()
            }
        }
    }

    CFBridgingRelease(queryRef)

    if (resultString != null) {
        return resultString!!
    }

    val newPassphrase = NSUUID().UUIDString() + NSUUID().UUIDString()
    val nsPassphrase = newPassphrase as NSString
    val newData = nsPassphrase.dataUsingEncoding(NSUTF8StringEncoding)!!

    val insertQuery = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        kSecAttrAccount to account,
        kSecValueData to newData,
        kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    )

    val insertRef = CFBridgingRetain(insertQuery) as CFDictionaryRef?
    SecItemAdd(insertRef, null)
    CFBridgingRelease(insertRef)

    return newPassphrase
}