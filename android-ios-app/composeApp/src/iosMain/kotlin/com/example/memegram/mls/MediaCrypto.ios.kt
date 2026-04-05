package com.example.memegram.mls

actual fun encryptMediaBytes(plainBytes: ByteArray): MediaEncryptResult {
    val fakeMetaB64 = kotlin.io.encoding.Base64.encode("""{"key":"stub","iv":"stub"}""".encodeToByteArray())
    return MediaEncryptResult(plainBytes, fakeMetaB64)
}

actual fun decryptMediaBytes(encryptedBytes: ByteArray, encryptionMetadataB64: String): ByteArray =
    encryptedBytes
