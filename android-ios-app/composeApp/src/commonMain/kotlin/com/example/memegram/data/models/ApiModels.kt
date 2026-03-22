package com.example.memegram.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    @SerialName("invite_code")      val inviteCode: String,
    @SerialName("device_id")        val deviceId: String,
    @SerialName("device_name")      val deviceName: String,
    @SerialName("identity_key_pub") val identityKeyPub: String,
    @SerialName("init_key_pub")     val initKeyPub: String,
    @SerialName("credential_data")  val credentialData: String
)

@Serializable data class LoginInitRequest(@SerialName("device_id") val deviceId: String)

@Serializable
data class LoginCompleteRequest(
    @SerialName("device_id")   val deviceId: String,
    val challenge: String,
    val signature: String,
    @SerialName("device_name") val deviceName: String? = null
)

@Serializable data class LogoutRequest(@SerialName("access_token") val accessToken: String)

@Serializable
data class AuthResponse(
    @SerialName("user_id")       val userId: String,
    @SerialName("device_id")     val deviceId: String,
    @SerialName("is_primary")    val isPrimary: Boolean,
    @SerialName("access_token")  val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at")    val expiresAt: Long
)

@Serializable
data class LoginInitResponse(
    val challenge: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("device_id")  val deviceId: String
)

@Serializable data class LogoutResponse(val success: Boolean, val message: String)

// User models

@Serializable
data class UserProfileResponse(
    val id: String,
    val username: String? = null,
    @SerialName("user_public_key")             val userPublicKey: String? = null,
    val bio: String? = null,
    @SerialName("is_deleted")                  val isDeleted: Boolean = false,
    @SerialName("avatar_media_id")             val avatarMediaId: String? = null,
    @SerialName("profile_background_media_id") val profileBackgroundMediaId: String? = null,
    @SerialName("last_active")                 val lastActive: Long? = null
)

@Serializable
data class UpdateProfileRequest(
    val username: String? = null,
    val bio: String? = null,
    @SerialName("avatar_media_id")             val avatarMediaId: String? = null,
    @SerialName("profile_background_media_id") val profileBackgroundMediaId: String? = null
)

@Serializable
data class UserSettingsResponse(
    @SerialName("user_id")                        val userId: String,
    val theme: String,
    val language: String,
    @SerialName("is_translator_active")           val isTranslatorActive: Boolean,
    @SerialName("animations_enabled")             val animationsEnabled: Boolean,
    @SerialName("account_auto_delete_after_days") val accountAutoDeleteAfterDays: Int? = null,
    @SerialName("profile_visible_to")             val profileVisibleTo: String,
    @SerialName("last_active_visible_to")         val lastActiveVisibleTo: String,
    @SerialName("top_bar_color")                  val topBarColor: String? = null,
    @SerialName("notification_vibration_strength") val notificationVibrationStrength: Int? = null,
    @SerialName("notification_sound")             val notificationSound: String? = null,
    @SerialName("ringtone_vibration_strength")    val ringtoneVibrationStrength: Int? = null
)

@Serializable
data class UpdateSettingsRequest(
    val theme: String? = null,
    val language: String? = null,
    @SerialName("top_bar_color")                  val topBarColor: String? = null,
    @SerialName("is_translator_active")           val isTranslatorActive: Boolean? = null,
    @SerialName("animations_enabled")             val animationsEnabled: Boolean? = null,
    @SerialName("profile_visible_to")             val profileVisibleTo: String? = null,
    @SerialName("last_active_visible_to")         val lastActiveVisibleTo: String? = null,
    @SerialName("account_auto_delete_after_days") val accountAutoDeleteAfterDays: Int? = null,
    @SerialName("notification_sound")             val notificationSound: String? = null,
    @SerialName("notification_vibration_strength") val notificationVibrationStrength: Int? = null,
    @SerialName("ringtone_vibration_strength")    val ringtoneVibrationStrength: Int? = null
)