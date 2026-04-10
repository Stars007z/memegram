package com.example.memegram.data.local

import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings

actual fun createSecureSettings(): Settings {
    return KeychainSettings(service = "com.example.memegram.session")
}