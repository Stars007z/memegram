package com.example.memegram.data.local

import com.russhwolf.settings.Settings
import com.example.memegram.data.models.AuthResponse

class SessionManager(private val settings: Settings) {

    fun save(r: AuthResponse) {
        settings.putString("access_token", r.accessToken)
        settings.putString("refresh_token", r.refreshToken)
        settings.putString("user_id", r.userId)
        settings.putString("device_id", r.deviceId)
        settings.putBoolean("is_primary", r.isPrimary)
        settings.putLong("expires_at", r.expiresAt)
    }

    fun getDeviceId(): String? = settings.getStringOrNull("device_id")

    fun getAccessToken(): String? = settings.getStringOrNull("access_token")

    val isLoggedIn: Boolean
        get() = getAccessToken() != null

    val isTokenExpired: Boolean
        get() {
            val exp = settings.getLong("expires_at", 0L)
            if (exp == 0L) return true
            return (kotlin.time.Clock.System.now().epochSeconds) > exp - 60
        }


    fun clear() {
        settings.clear()
    }

    fun saveProfile(username: String, bio: String) {
        settings.putString("profile_username", username)
        settings.putString("profile_bio", bio)
    }

    fun getUsername(): String {
        return settings.getString("profile_username", "Влад")
    }

    fun getBio(): String {
        return settings.getString("profile_bio", "KMP Developer")
    }
}