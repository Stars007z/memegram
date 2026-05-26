package com.example.memegram.notifications

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationPrefs(private val settings: Settings) {

    companion object {
        private const val KEY_VIBRATION_STRENGTH = "notif_vibration_strength"

        const val DEFAULT_VIBRATION_STRENGTH = 2

        const val VIBRATION_MIN = 0
        const val VIBRATION_MAX = 3
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

    fun vibrationStrengthNow(): Int = _vibrationStrength.value
}
