package com.example.memegram.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RegisterRequest(
    val username: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("identity_key_pub") val identityKeyPub: String,
    @SerialName("init_key_pub") val initKeyPub: String,
    @SerialName("credential_data") val credentialData: String
)

@Serializable data class LoginInitRequest(@SerialName("device_id") val deviceId: String)

@Serializable
data class LoginCompleteRequest(
    @SerialName("device_id") val deviceId: String,
    val challenge: String,
    val signature: String,
    @SerialName("device_name") val deviceName: String? = null
)

@Serializable data class LogoutRequest(@SerialName("access_token") val accessToken: String)

@Serializable
data class AuthResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("is_primary") val isPrimary: Boolean,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long
)

@Serializable
data class LoginInitResponse(
    val challenge: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("device_id") val deviceId: String
)

@Serializable data class LogoutResponse(val success: Boolean, val message: String)

@Serializable
data class UserProfileResponse(
    val id: String,
    val username: String? = null,
    @SerialName("user_public_key") val userPublicKey: String? = null,
    val bio: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("avatar_media_id") val avatarMediaId: String? = null,
    @SerialName("profile_background_media_id") val profileBackgroundMediaId: String? = null,
    @SerialName("last_active") val lastActive: Long? = null
)

@Serializable
data class UpdateProfileRequest(
    val username: String? = null,
    val bio: String? = null,
    @SerialName("avatar_media_id") val avatarMediaId: String? = null,
    @SerialName("profile_background_media_id") val profileBackgroundMediaId: String? = null
)

@Serializable
data class UserSettingsResponse(
    @SerialName("user_id") val userId: String,
    val theme: String,
    val language: String,
    @SerialName("is_translator_active") val isTranslatorActive: Boolean,
    @SerialName("animations_enabled") val animationsEnabled: Boolean,
    @SerialName("account_auto_delete_after_days") val accountAutoDeleteAfterDays: Int? = null,
    @SerialName("profile_visible_to") val profileVisibleTo: String,
    @SerialName("last_active_visible_to") val lastActiveVisibleTo: String,
    @SerialName("top_bar_color") val topBarColor: String? = null,
    @SerialName("notification_vibration_strength") val notificationVibrationStrength: Int? = null,
    @SerialName("notification_sound") val notificationSound: String? = null,
    @SerialName("ringtone_vibration_strength") val ringtoneVibrationStrength: Int? = null
)

@Serializable
data class UpdateSettingsRequest(
    val theme: String? = null,
    val language: String? = null,
    @SerialName("top_bar_color") val topBarColor: String? = null,
    @SerialName("is_translator_active") val isTranslatorActive: Boolean? = null,
    @SerialName("animations_enabled") val animationsEnabled: Boolean? = null,
    @SerialName("profile_visible_to") val profileVisibleTo: String? = null,
    @SerialName("last_active_visible_to") val lastActiveVisibleTo: String? = null,
    @SerialName("account_auto_delete_after_days") val accountAutoDeleteAfterDays: Int? = null,
    @SerialName("notification_sound") val notificationSound: String? = null,
    @SerialName("notification_vibration_strength") val notificationVibrationStrength: Int? = null,
    @SerialName("ringtone_vibration_strength") val ringtoneVibrationStrength: Int? = null
)

@Serializable
data class ConversationResponse(
    val id: String,
    val type: String = "",
    val name: String? = null,
    val members: List<ConversationMember> = emptyList(),
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    @SerialName("unread_count") val unreadCount: Int? = null
)

@Serializable
data class SendMessageRequest(
    @SerialName("mls_ciphertext") val mlsCiphertextB64: String,
    val type: String,
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null
)

@Serializable
data class WelcomeResponse(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("welcome_data") val welcomeDataB64: String
)

@Serializable
data class CommitResponse(
    @SerialName("commit_data") val commitDataB64: String,
    val epoch: Long,
    @SerialName("created_at") val createdAt: Long = 0L
)

@Serializable
internal data class WelcomesEnvelope(val items: List<WelcomeResponse>)

@Serializable
internal data class CommitsEnvelope(val commits: List<CommitResponse>)

@Serializable
data class SseEventData(
    val id: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("sender_user_id") val senderUserId: String? = null,
    @SerialName("sender_device_id") val senderDeviceId: String? = null,
    @SerialName("mls_ciphertext") val mlsCiphertextB64: String? = null,
    @SerialName("new_mls_ciphertext") val newMlsCiphertextB64: String? = null,
    @SerialName("created_at") val createdAt: Long = 0L
)

@Serializable
data class SseEvent(
    @SerialName("conversation_id") val conversationId: String = "",
    val type: String,
    val data: SseEventData? = null
)


@Serializable
data class CreateDirectConversationRequest(
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("welcome_messages")  val welcomeMessages: List<DeviceWelcome> = emptyList()
)

@Serializable
data class KeyPackageResponse(
    @SerialName("key_package_data") val keyPackageB64: String,
    @SerialName("key_package_ref") val keyPackageRef: String = "",
    @SerialName("device_id") val deviceId: String = ""
)

@Serializable
data class CommitGroupChangeRequest(
    @SerialName("commit_data") val commitData: String,
    @SerialName("new_epoch") val newEpoch: Int,
    @SerialName("welcome_messages") val welcomeMessages: List<DeviceWelcome> = emptyList(),
    @SerialName("ratchet_tree") val ratchetTree: String? = null,
    @SerialName("removed_device_ids") val removedDeviceIds: List<String> = emptyList()
)

@Serializable
data class SendMessageResponse(
    @SerialName("message_id") val messageId: String,
    @SerialName("created_at") val createdAt: Long = 0L
)
@Serializable
internal data class KeyPackagesForUserEnvelope(
    @SerialName("key_packages") val keyPackages: List<KeyPackageResponse>
)
@Serializable
internal data class ConversationEnvelope(
    val conversation: ConversationResponse
)
@Serializable
data class MarkAsReadRequest(
    @SerialName("last_read_message_id")
    val lastReadMessageId: String
)
@Serializable
data class ConversationSummary(
    val id: String,
    val type: String = "",
    val name: String? = null,
    @SerialName("last_message_type") val lastMessageType: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_activity_at") val lastActivityAt: Long = 0L
)

@Serializable
data class GetConversationsResponse(
    val items: List<ConversationSummary>,
    @SerialName("next_cursor") val nextCursor: String = ""
)
@Serializable
data class ConversationMember(
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: Long = 0L
)
@Serializable
data class MessageResponse(
    val id: String,
    @SerialName("sender_user_id") val senderId: String = "",
    @SerialName("sender_device_id") val senderDeviceId: String? = null,
    @SerialName("mls_ciphertext") val mlsCiphertextB64: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("sender_id") val fallbackSenderId: String? = null,
    @SerialName("media_id")            val mediaId: String? = null,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    val type: String? = null
) {
    val effectiveSenderId: String
        get() = senderId.takeIf { it.isNotBlank() } ?: fallbackSenderId ?: ""
}

@Serializable
data class GetMessagesResponse(
    val messages: List<MessageResponse> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false
)


@Serializable
data class InitiateMediaUploadRequest(
    @SerialName("conversation_id")    val conversationId: String,
    @SerialName("mime_type")          val mimeType: String,
    @SerialName("encrypted_size")     val encryptedSize: Long,
    @SerialName("encryption_metadata") val encryptionMetadata: String
)

@Serializable
data class InitiateMediaUploadResponse(
    @SerialName("media_id")   val mediaId: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("expires_in") val expiresIn: Int
)

@Serializable
data class ConfirmMediaUploadResponse(
    val success: Boolean
)

@Serializable
data class GetMediaDownloadUrlResponse(
    @SerialName("download_url")        val downloadUrl: String,
    @SerialName("expires_in")          val expiresIn: Int,
    @SerialName("encryption_metadata") val encryptionMetadata: String
)

@Serializable
data class UserDeviceKeyPackage(
    @SerialName("device_id")        val deviceId: String,
    @SerialName("key_package_data") val keyPackageData: String,
    @SerialName("key_package_ref")  val keyPackageRef: String
)

@Serializable
internal data class GetKeyPackagesForUserResponse(
    @SerialName("key_packages") val keyPackages: List<UserDeviceKeyPackage>
)
@Serializable
data class MemberWelcomes(
    @SerialName("user_id") val userId: String,
    @SerialName("welcomes") val welcomes: List<DeviceWelcome>
)

@Serializable
data class CreateGroupConversationRequest(
    @SerialName("name") val name: String,
    @SerialName("members") val members: List<MemberWelcomes>
)
