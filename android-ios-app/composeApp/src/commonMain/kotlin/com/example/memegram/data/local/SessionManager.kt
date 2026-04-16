package com.example.memegram.data.local

import com.russhwolf.settings.Settings
import com.example.memegram.data.models.AuthResponse
import com.example.memegram.data.models.ConfirmDeviceAdditionResponse

class SessionManager(private val settings: Settings) {

    fun save(r: AuthResponse) {
        settings.putString("access_token", r.accessToken)
        settings.putString("refresh_token", r.refreshToken)
        settings.putString("user_id", r.userId)
        settings.putString("device_id", r.deviceId)
        settings.putString("device_type", r.deviceType)
        settings.putLong("expires_at", r.expiresAt)
    }

    fun getDeviceId(): String? = settings.getStringOrNull("device_id")
    fun getUserId(): String? = settings.getStringOrNull("user_id")
    fun getAccessToken(): String? = settings.getStringOrNull("access_token")
    fun getDeviceType(): String? = settings.getStringOrNull("device_type")

    val isLoggedIn: Boolean
        get() = getAccessToken() != null

    val isTokenExpired: Boolean
        get() {
            val exp = settings.getLong("expires_at", 0L)
            if (exp == 0L) return true
            return (kotlin.time.Clock.System.now().epochSeconds) > exp - 60
        }


    fun clear() {
        settings.remove("access_token")
        settings.remove("refresh_token")
        settings.remove("expires_at")
        settings.remove("device_type")
    }

    fun saveProfile(username: String, bio: String) {
        settings.putString("profile_username", username)
        settings.putString("profile_bio", bio)
    }

    fun getUsername(): String {
        return settings.getString("profile_username", "")
    }

    fun getBio(): String {
        return settings.getString("profile_bio", "KMP Developer")
    }

    fun clearDeviceId() {
        settings.remove("device_id")
        settings.remove("user_id")
    }

    fun saveSession(response: ConfirmDeviceAdditionResponse) {
        settings.putString("access_token", response.accessToken)
        settings.putString("refresh_token", response.refreshToken)
        settings.putString("user_id",       response.userId)
        settings.putString("device_id",     response.newDeviceId)
        settings.putString("device_type",   "secondary")
        settings.putLong("expires_at",      response.expiresAt)
    }
}