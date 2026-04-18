package com.example.memegram.data.gallery

actual suspend fun AttachItem.readUploadBytes(): ByteArray = when (this) {
    is AttachItem.FromPicker  -> file.readBytes()
    is AttachItem.FromGallery -> thumb.bytes
}

actual fun AttachItem.guessMimeType(): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return mimeFromExtension(ext)
}

private fun mimeFromExtension(ext: String): String = when (ext) {
    // Images
    "jpg", "jpeg" -> "image/jpeg"
    "png"         -> "image/png"
    "gif"         -> "image/gif"
    "webp"        -> "image/webp"
    "heic", "heif" -> "image/heic"
    "bmp"         -> "image/bmp"
    "svg"         -> "image/svg+xml"
    // Video
    "mp4", "m4v"  -> "video/mp4"
    "mov"         -> "video/quicktime"
    "avi"         -> "video/x-msvideo"
    "mkv"         -> "video/x-matroska"
    "webm"        -> "video/webm"
    // Audio
    "mp3"         -> "audio/mpeg"
    "m4a", "aac"  -> "audio/mp4"
    "wav"         -> "audio/wav"
    "ogg", "opus" -> "audio/ogg"
    "flac"        -> "audio/flac"
    // Docs
    "pdf"         -> "application/pdf"
    "doc"         -> "application/msword"
    "docx"        -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls"         -> "application/vnd.ms-excel"
    "xlsx"        -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt"         -> "application/vnd.ms-powerpoint"
    "pptx"        -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "txt", "log"  -> "text/plain"
    "json"        -> "application/json"
    "xml"         -> "application/xml"
    "csv"         -> "text/csv"
    "html", "htm" -> "text/html"
    // Archives
    "zip"         -> "application/zip"
    "rar"         -> "application/vnd.rar"
    "7z"          -> "application/x-7z-compressed"
    "tar"         -> "application/x-tar"
    "gz"          -> "application/gzip"
    else          -> "application/octet-stream"
}
