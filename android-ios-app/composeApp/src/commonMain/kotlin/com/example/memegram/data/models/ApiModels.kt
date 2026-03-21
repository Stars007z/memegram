package com.example.memegram.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    @SerialName("invite_code")     val inviteCode: String,
    @SerialName("device_id")       val deviceId: String,
    @SerialName("device_name")     val deviceName: String,
    @SerialName("identity_key_pub") val identityKeyPub: String,
    @SerialName("init_key_pub")    val initKeyPub: String,
    @SerialName("credential_data") val credentialData: String
)

@Serializable
data class LoginInitRequest(
    @SerialName("device_id") val deviceId: String
)

@Serializable
data class LoginCompleteRequest(
    @SerialName("device_id") val deviceId: String,
    val challenge: String,
    val signature: String,
    @SerialName("device_name") val deviceName: String? = null
)

@Serializable
data class LogoutRequest(
    @SerialName("access_token") val accessToken: String
)

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

@Serializable
data class LogoutResponse(
    val success: Boolean,
    val message: String
)