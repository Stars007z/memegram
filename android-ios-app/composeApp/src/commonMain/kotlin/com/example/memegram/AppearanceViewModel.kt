package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.InitiateItemUploadRequest
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.UserRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class AppearanceViewModel(
    private val themePreferences: ThemePreferences,
    private val userRepository: UserRepository,
    private val api: ApiService,
    private val settings: Settings,
    private val appearance: AppearanceRepository,
) : ViewModel() {

    val chatBgColor: StateFlow<Color> = appearance.chatBgColor
    val myBubbleColor: StateFlow<Color> = appearance.myBubbleColor
    val theirBubbleColor: StateFlow<Color> = appearance.theirBubbleColor

    val chatBgImage: StateFlow<ByteArray?> = appearance.chatBgImage
    val topBarImage: StateFlow<ByteArray?> = appearance.topBarImage
    val myBubbleImage: StateFlow<ByteArray?> = appearance.myBubbleImage
    val theirBubbleImage: StateFlow<ByteArray?> = appearance.theirBubbleImage

    val transparentBubbles: StateFlow<Boolean> = appearance.transparentBubbles
    val bubbleTransparency: StateFlow<Float> = appearance.bubbleTransparency
    val myBubbleTextColor: StateFlow<Color?> = appearance.myBubbleTextColor
    val theirBubbleTextColor: StateFlow<Color?> = appearance.theirBubbleTextColor
    val topBarTextColor: StateFlow<Color?> = appearance.topBarTextColor
    val chatBgTextColor: StateFlow<Color?> = appearance.chatBgTextColor

    fun setTransparentBubbles(enabled: Boolean) {
        appearance.setTransparentBubbles(enabled)
    }

    fun setBubbleTransparency(value: Float) {
        appearance.setBubbleTransparency(value)
    }

    fun updateTextColor(surfaceKey: String, color: Color) {
        appearance.setTextColor(surfaceKey, color)
    }

    fun resetTextColor(surfaceKey: String) {
        appearance.clearTextColor(surfaceKey)
    }

    fun clearImage(key: String) {
        appearance.clearImage(key)
        val field = when (key) {
            "chatbg" -> "chat_background_media_id"
            "topbar" -> "top_bar_media_id"
            "mybubble" -> "my_bubble_media_id"
            "theirbubble" -> "their_bubble_media_id"
            else -> return
        }
        syncClearMediaToServer(field)
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { s ->
                s.topBarColor?.let { hex ->
                    runCatching {
                        val colorInt = hex.removePrefix("#").toLong(16).toInt()
                        appearance.setColor("topbar", Color(colorInt or 0xFF000000.toInt()))
                    }
                }
            }
        }
    }

    fun updateColor(key: String, color: Color) {
        appearance.setColor(key, color)
        when (key) {
            "chatbg" -> {
                appearance.clearImage("chatbg")
                syncClearMediaToServer("chat_background_media_id")
            }
            "mybubble" -> {
                appearance.clearImage("mybubble")
                syncClearMediaToServer("my_bubble_media_id")
            }
            "theirbubble" -> {
                appearance.clearImage("theirbubble")
                syncClearMediaToServer("their_bubble_media_id")
            }
            "topbar" -> {
                syncTopBarToServer(color)
                appearance.clearImage("topbar")
                syncClearMediaToServer("top_bar_media_id")
            }
        }
    }

    fun updateImage(key: String, bytes: ByteArray) {
        val itemType = when (key) {
            "chatbg" -> "chat_background"
            "topbar" -> "top_bar"
            "mybubble" -> "my_bubble"
            "theirbubble" -> "their_bubble"
            else -> return
        }

        appearance.setImage(key, bytes)

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val mediaId = uploadImageToItemStorage(bytes, itemType, "image/jpeg")
                settings.putString("appearance_${key}_image_media_id", mediaId)

                val request = when (key) {
                    "chatbg" -> UpdateSettingsRequest(chatBackgroundMediaId = mediaId)
                    "topbar" -> UpdateSettingsRequest(topBarMediaId = mediaId)
                    "mybubble" -> UpdateSettingsRequest(myBubbleMediaId = mediaId)
                    "theirbubble" -> UpdateSettingsRequest(theirBubbleMediaId = mediaId)
                    else -> null
                }
                request?.let { userRepository.updateSettings(it) }
            } catch (e: Exception) {
                println("AppearanceVM: Failed to upload image: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun syncClearMediaToServer(fieldName: String) {
        viewModelScope.launch {
            val request = when (fieldName) {
                "chat_background_media_id" -> UpdateSettingsRequest(chatBackgroundMediaId = "")
                "top_bar_media_id" -> UpdateSettingsRequest(topBarMediaId = "")
                "my_bubble_media_id" -> UpdateSettingsRequest(myBubbleMediaId = "")
                "their_bubble_media_id" -> UpdateSettingsRequest(theirBubbleMediaId = "")
                else -> null
            }
            request?.let { runCatching { userRepository.updateSettings(it) } }
        }
    }

    private fun syncTopBarToServer(color: Color) {
        viewModelScope.launch {
            val hex = "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
            userRepository.updateSettings(UpdateSettingsRequest(topBarColor = hex))
        }
    }

    private suspend fun uploadImageToItemStorage(
        bytes: ByteArray,
        itemType: String,
        mimeType: String
    ): String {
        val initiateResp = api.initiateItemUpload(
            InitiateItemUploadRequest(
                itemType = itemType,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong()
            )
        )
        api.uploadBytesToPresignedUrl(initiateResp.uploadUrl, bytes, mimeType)
        val confirmResp = api.confirmItemUpload(initiateResp.itemId)
        if (!confirmResp.success) {
            throw Exception("Upload confirmation failed for item ${initiateResp.itemId}")
        }
        return initiateResp.itemId
    }
}
