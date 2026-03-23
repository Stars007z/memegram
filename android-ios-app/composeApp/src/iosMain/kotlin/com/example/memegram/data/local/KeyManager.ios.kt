package com.example.memegram.data.local

import com.russhwolf.settings.Settings

actual fun createPlatformKeyManager(settings: Settings): KeyManager =
    CommonKeyManager(settings)
