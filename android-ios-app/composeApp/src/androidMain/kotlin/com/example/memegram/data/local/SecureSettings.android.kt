package com.example.memegram.data.local

import com.example.memegram.AppContextHolder
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

actual fun createSecureSettings(): Settings {
    val sharedPreferences = SecurePrefsFactory.create(
        AppContextHolder.context,
        "session_secure_prefs",
    )
    return SharedPreferencesSettings(sharedPreferences)
}
