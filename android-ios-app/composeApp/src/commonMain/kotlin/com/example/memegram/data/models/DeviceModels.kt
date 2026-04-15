package com.example.memegram.data.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InitDeviceAdditionResponse(
    @SerialName("registration_id")   val registrationId: String,
    @SerialName("expires_at")        val expiresAt: Long,
    @SerialName("registration_code") val registrationCode: String
)

@Serializable
data class SubmitDeviceDataRequest(
    @SerialName("device_id")         val deviceId: String,
    @SerialName("device_name")       val deviceName: String,
    @SerialName("device_type")       val deviceType: String = "secondary",
    @SerialName("identity_key_pub")  val identityKeyPub: String,
    @SerialName("init_key_pub")      val initKeyPub: String,
    @SerialName("credential_data")   val credentialData: String,
    @SerialName("registration_code") val registrationCode: String
)

@Serializable
data class SubmitDeviceDataResponse(
    @SerialName("status")     val status: String,
    @SerialName("expires_at") val expiresAt: Long
)

@Serializable
data class DeviceInfoResponse(
    @SerialName("id")               val id: String,
    @SerialName("user_id")          val userId: String,
    @SerialName("client_device_id") val clientDeviceId: String,
    @SerialName("device_name")      val deviceName: String,
    @SerialName("device_type")      val deviceType: String,
    @SerialName("is_active")        val isActive: Boolean,
    @SerialName("created_at")       val createdAt: Long,
    @SerialName("last_seen")        val lastSeen: Long,
    @SerialName("identity_key_pub") val identityKeyPub: String,
    @SerialName("init_key_pub")     val initKeyPub: String,
    @SerialName("revoked_at")       val revokedAt: Long? = null
)

@Serializable
data class DeviceAdditionStatusResponse(
    @SerialName("status")           val status: String,
    @SerialName("expires_at")       val expiresAt: Long,
    @SerialName("device")           val device: DeviceInfoResponse? = null,
    @SerialName("access_token")     val accessToken: String? = null,
    @SerialName("refresh_token")    val refreshToken: String? = null,
    @SerialName("token_expires_at") val tokenExpiresAt: Long = 0L
)

@Serializable
data class PendingDeviceRegistration(
    @SerialName("registration_id")   val registrationId: String,
    @SerialName("registration_code") val registrationCode: String,
    @SerialName("expires_at")        val expiresAt: Long,
    @SerialName("status")            val status: String,
    @SerialName("device_id")         val deviceId: String,
    @SerialName("device_name")       val deviceName: String,
    @SerialName("device_type")       val deviceType: String,
    @SerialName("created_at")        val createdAt: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfirmDeviceAdditionRequest(
    @SerialName("confirm")         val confirm: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("new_device_name") val newDeviceName: String? = null
)

@Serializable
data class ConfirmDeviceAdditionResponse(
    @SerialName("new_device_id") val newDeviceId: String,
    @SerialName("user_id")       val userId: String,
    @SerialName("status")        val status: String,
    @SerialName("message")       val message: String,
    @SerialName("access_token")  val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at")    val expiresAt: Long
)

@Serializable
data class RevokeDeviceRequest(
    @SerialName("reason") val reason: String = "No reason provided"
)

@Serializable
data class RevokeDeviceResponse(
    @SerialName("success")           val success: Boolean,
    @SerialName("message")           val message: String,
    @SerialName("revoked_device_id") val revokedDeviceId: String,
    @SerialName("revoked_at")        val revokedAt: Long
)

@Serializable
data class DeviceWelcome(
    @SerialName("device_id")    val deviceId: String,
    @SerialName("welcome_data") val welcomeData: String
)

@Serializable
data class CommitGroupChangeResponse(
    @SerialName("new_epoch")    val newEpoch: Int,
    @SerialName("committed_at") val committedAt: Long
)
@Serializable
data class UpdateDeviceKeysRequest(
    @SerialName("identity_key_pub") val identityKeyPub: String,
    @SerialName("init_key_pub") val initKeyPub: String,
    @SerialName("credential_data") val credentialData: String
)

@Serializable
data class UpdateDeviceKeysResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("message") val message: String,
    @SerialName("updated_at") val updatedAt: Long
)