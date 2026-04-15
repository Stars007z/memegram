package com.example.memegram.utils

import java.util.UUID

actual fun generateUuid(): String {
    return UUID.randomUUID().toString()
}