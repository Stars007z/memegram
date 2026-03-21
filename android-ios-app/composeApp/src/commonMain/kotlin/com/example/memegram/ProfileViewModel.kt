package com.example.memegram

import androidx.lifecycle.ViewModel
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ProfileViewModel(private val settings: Settings) : ViewModel() {

    companion object {
        const val KEY_USERNAME = "profile_username"
        const val KEY_BIO     = "profile_bio"
        const val KEY_AVATAR  = "profile_avatar_b64"
        const val KEY_COVER   = "profile_cover_b64"
    }

    private val _username = MutableStateFlow(settings.getString(KEY_USERNAME, "User"))
    val username: StateFlow<String> = _username.asStateFlow()

    private val _bio = MutableStateFlow(settings.getString(KEY_BIO, ""))
    val bio: StateFlow<String> = _bio.asStateFlow()

    private val _avatarBytes = MutableStateFlow(
        settings.getStringOrNull(KEY_AVATAR)?.let { Base64.decode(it) }
    )
    val avatarBytes: StateFlow<ByteArray?> = _avatarBytes.asStateFlow()

    private val _coverBytes = MutableStateFlow(
        settings.getStringOrNull(KEY_COVER)?.let { Base64.decode(it) }
    )
    val coverBytes: StateFlow<ByteArray?> = _coverBytes.asStateFlow()

    fun updateUsername(name: String) {
        settings.putString(KEY_USERNAME, name)
        _username.value = name
    }

    fun updateBio(newBio: String) {
        settings.putString(KEY_BIO, newBio)
        _bio.value = newBio
    }

    fun updateAvatar(bytes: ByteArray?) {
        _avatarBytes.value = bytes
        if (bytes != null) settings.putString(KEY_AVATAR, Base64.encode(bytes))
        else settings.remove(KEY_AVATAR)
    }

    fun updateCover(bytes: ByteArray?) {
        _coverBytes.value = bytes
        if (bytes != null) settings.putString(KEY_COVER, Base64.encode(bytes))
        else settings.remove(KEY_COVER)
    }
}