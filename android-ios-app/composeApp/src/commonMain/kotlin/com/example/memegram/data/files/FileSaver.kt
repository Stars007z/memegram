package com.example.memegram.data.files

expect suspend fun saveDownloadedFile(
    bytes: ByteArray,
    fileName: String,
    mime: String
): String?

expect suspend fun openSavedFile(pathOrUri: String, mime: String): Boolean
