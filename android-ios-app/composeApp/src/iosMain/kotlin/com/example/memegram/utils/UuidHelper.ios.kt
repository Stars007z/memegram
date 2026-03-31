package com.example.memegram.utils

import platform.Foundation.NSUUID

actual fun generateUuid(): String {
    return NSUUID().UUIDString()
}