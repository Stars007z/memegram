package com.example.memegram.data.gallery

expect suspend fun AttachItem.readUploadBytes(): ByteArray
expect fun AttachItem.guessMimeType(): String
