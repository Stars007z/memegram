package com.example.memegram.mls

data class MediaEncryptResult(
    val encryptedBytes: ByteArray,
    val encryptionMetadataB64: String
)

expect fun encryptMediaBytes(plainBytes: ByteArray): MediaEncryptResult
expect fun decryptMediaBytes(encryptedBytes: ByteArray, encryptionMetadataB64: String): ByteArray
