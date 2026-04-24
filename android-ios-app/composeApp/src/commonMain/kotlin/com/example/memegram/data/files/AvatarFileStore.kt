package com.example.memegram.data.files

expect fun getAvatarsCacheDir(): String

expect suspend fun readAvatarBytes(mediaId: String): ByteArray?

expect suspend fun writeAvatarBytes(mediaId: String, bytes: ByteArray)

expect suspend fun deleteAvatarBytes(mediaId: String)

expect suspend fun clearAvatarsCache()

expect suspend fun avatarsCacheSizeBytes(): Long
