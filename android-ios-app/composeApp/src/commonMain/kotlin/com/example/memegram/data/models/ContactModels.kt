package com.example.memegram.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContactBriefProfile(
    @SerialName("user_id")         val userId: String = "",
    val username: String = "",
    @SerialName("user_public_key") val userPublicKey: String? = null,
    val bio: String = "",
    @SerialName("avatar_media_id") val avatarMediaId: String? = null
)

@Serializable
data class ContactEntry(
    @SerialName("contact_user_id") val contactUserId: String,
    @SerialName("is_favorite")     val isFavorite: Boolean = false,
    @SerialName("created_at")      val createdAt: Long = 0L,
    val profile: ContactBriefProfile? = null
)

@Serializable
data class ContactsListResponse(
    val contacts: List<ContactEntry>,
    @SerialName("total_count") val totalCount: Int
)

@Serializable
data class AddContactRequest(
    @SerialName("user_public_key") val userPublicKey: String
)

@Serializable
data class UpdateContactRequest(
    @SerialName("contact_user_id") val contactUserId: String,
    @SerialName("is_favorite")     val isFavorite: Boolean
)

@Serializable
data class BlockedEntry(
    @SerialName("blocked_user_id") val blockedUserId: String,
    @SerialName("blocked_at")      val blockedAt: Long = 0L,
    val profile: ContactBriefProfile? = null
)

@Serializable
data class BlockedUsersListResponse(
    @SerialName("blocked_users") val blockedUsers: List<BlockedEntry>,
    @SerialName("total_count")   val totalCount: Int
)

@Serializable
data class BlockUserRequest(
    @SerialName("blocked_user_id") val blockedUserId: String
)

@Serializable
data class RemoveContactResponse(val success: Boolean)

@Serializable
data class BlockUserResponse(
    val success: Boolean,
    @SerialName("created_at") val createdAt: Long = 0L
)

@Serializable
data class UnblockUserResponse(val success: Boolean)