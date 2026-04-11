package com.example.memegram.mls

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

actual fun encryptMediaBytes(plainBytes: ByteArray): MediaEncryptResult {
    val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val iv  = ByteArray(12).also { SecureRandom().nextBytes(it) }

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key, "AES"),
        GCMParameterSpec(128, iv)
    )
    val encrypted = cipher.doFinal(plainBytes)

    val meta = """{"key":"${Base64.encodeToString(key, Base64.NO_WRAP)}","iv":"${Base64.encodeToString(iv, Base64.NO_WRAP)}"}"""
    val metaB64 = Base64.encodeToString(meta.encodeToByteArray(), Base64.NO_WRAP)

    return MediaEncryptResult(encrypted, metaB64)
}

actual fun decryptMediaBytes(encryptedBytes: ByteArray, encryptionMetadataB64: String): ByteArray {
    val metaJson = Base64.decode(encryptionMetadataB64, Base64.NO_WRAP).decodeToString()
    val keyB64 = Regex(""""key"\s*:\s*"([^"]+)"""").find(metaJson)!!.groupValues[1]
    val ivB64  = Regex(""""iv"\s*:\s*"([^"]+)"""").find(metaJson)!!.groupValues[1]

    val key = Base64.decode(keyB64, Base64.NO_WRAP)
    val iv  = Base64.decode(ivB64,  Base64.NO_WRAP)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        GCMParameterSpec(128, iv)
    )
    return cipher.doFinal(encryptedBytes)
}
