package com.example.memegram.data.gallery

actual suspend fun AttachItem.readUploadBytes(): ByteArray = when (this) {
    is AttachItem.FromPicker  -> file.readBytes()
    is AttachItem.FromGallery -> thumb.bytes
}

actual fun AttachItem.guessMimeType(): String = "image/jpeg"
