package com.example.memegram

// Запросы

data class RegisterRequest(
    val username: String,
    val invite_code: String,
    val device_id: String,
    val device_name: String,
    val identity_key_pub: String,
    val init_key_pub: String,
    val credential_data: String
)

data class LoginInitRequest(val device_id: String)

data class LoginCompleteRequest(
    val device_id: String,
    val challenge: String,
    val signature: String,
    val device_name: String? = null
)

data class LogoutRequest(val access_token: String)

// Ответы

data class AuthResponse(
    val user_id: String,
    val device_id: String,
    val is_primary: Boolean,
    val access_token: String,
    val refresh_token: String,
    val expires_at: Long
)

data class LoginInitResponse(
    val challenge: String,
    val expires_at: Long,
    val device_id: String
)

data class LogoutResponse(val success: Boolean, val message: String)