package com.example.memegram.notifications

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationPrefs(private val settings: Settings) {

    companion object {
        private const val KEY_PREVIEW_ENABLED = "notif_preview_enabled"
        private const val KEY_VIBRATION_STRENGTH = "notif_vibration_strength"

        const val DEFAULT_PREVIEW_ENABLED = true
        const val DEFAULT_VIBRATION_STRENGTH = 2

        const val VIBRATION_MIN = 0
        const val VIBRATION_MAX = 3
    }

    private val _previewEnabled = MutableStateFlow(
        settings.getBoolean(KEY_PREVIEW_ENABLED, DEFAULT_PREVIEW_ENABLED)
    )
    val previewEnabled: StateFlow<Boolean> = _previewEnabled.asStateFlow()

    fun setPreviewEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_PREVIEW_ENABLED, enabled)
        _previewEnabled.value = enabled
    }

    private val _vibrationStrength = MutableStateFlow(
        settings.getInt(KEY_VIBRATION_STRENGTH, DEFAULT_VIBRATION_STRENGTH)
            .coerceIn(VIBRATION_MIN, VIBRATION_MAX)
    )
    val vibrationStrength: StateFlow<Int> = _vibrationStrength.asStateFlow()

    fun setVibrationStrength(strength: Int) {
        val coerced = strength.coerceIn(VIBRATION_MIN, VIBRATION_MAX)
        settings.putInt(KEY_VIBRATION_STRENGTH, coerced)
        _vibrationStrength.value = coerced
    }

    fun previewEnabledNow(): Boolean = _previewEnabled.value
    fun vibrationStrengthNow(): Int = _vibrationStrength.value
}
