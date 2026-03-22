package com.example.memegram.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val bio: String? = null,
    @SerialName("avatar_media_id")              val avatarMediaId: String? = null,
    @SerialName("profile_background_media_id")  val profileBackgroundMediaId: String? = null,
    @SerialName("user_public_key")              val userPublicKey: String? = null,
    @SerialName("last_active")                  val lastActive: String? = null,
    @SerialName("is_deleted")                   val isDeleted: Boolean = false
)

@Serializable
data class UserSettings(
    val theme: String? = null,
    val language: String? = null,
    @SerialName("is_translator_active")           val isTranslatorActive: Boolean? = null,
    @SerialName("animations_enabled")             val animationsEnabled: Boolean? = null,
    @SerialName("top_bar_color")                  val topBarColor: String? = null,
    @SerialName("profile_visible_to")             val profileVisibleTo: String? = null,
    @SerialName("last_active_visible_to")         val lastActiveVisibleTo: String? = null,
    @SerialName("account_auto_delete_after_days") val accountAutoDeleteAfterDays: Int? = null
)