package com.example.memegram.mls

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
private val provider: CryptographyProvider by lazy { CryptographyProvider.Default }

@OptIn(ExperimentalEncodingApi::class)
actual fun encryptMediaBytes(plainBytes: ByteArray): MediaEncryptResult = runBlocking {
    val aesGcm = provider.get(AES.GCM)
    val key = aesGcm.keyGenerator(AES.Key.Size.B256).generateKey()

    val cipher = key.cipher()
    val combined = cipher.encrypt(plaintext = plainBytes)

    val ivSize = 12
    val iv = combined.copyOfRange(0, ivSize)
    val ciphertextWithTag = combined.copyOfRange(ivSize, combined.size)

    val keyBytes = key.encodeToByteArray(AES.Key.Format.RAW)
    val keyB64 = Base64.encode(keyBytes)
    val ivB64 = Base64.encode(iv)

    val metaJson = """{"key":"$keyB64","iv":"$ivB64"}"""
    val metaB64 = Base64.encode(metaJson.encodeToByteArray())

    MediaEncryptResult(ciphertextWithTag, metaB64)
}

@OptIn(ExperimentalEncodingApi::class)
actual fun decryptMediaBytes(encryptedBytes: ByteArray, encryptionMetadataB64: String): ByteArray = runBlocking {
    require(encryptionMetadataB64.isNotBlank()) {
        "decryptMediaBytes: encryptionMetadataB64 is blank — нечем расшифровывать медиа"
    }
    val metaJson = Base64.decode(encryptionMetadataB64).decodeToString()
    val keyB64 = Regex(""""key"\s*:\s*"([^"]+)"""").find(metaJson)?.groupValues?.get(1)
        ?: throw IllegalArgumentException("decryptMediaBytes: 'key' missing in metadata JSON: $metaJson")
    val ivB64 = Regex(""""iv"\s*:\s*"([^"]+)"""").find(metaJson)?.groupValues?.get(1)
        ?: throw IllegalArgumentException("decryptMediaBytes: 'iv' missing in metadata JSON: $metaJson")

    val keyBytes = Base64.decode(keyB64)
    val iv = Base64.decode(ivB64)

    val aesGcm = provider.get(AES.GCM)
    val key = aesGcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)

    val combined = iv + encryptedBytes

    key.cipher().decrypt(ciphertext = combined)
}
