package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.localization.AppStrings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel(
    private val settings: Settings,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _vibrateStrength = MutableStateFlow(settings.getInt("notif_vibrate_strength", 2))
    val vibrateStrength: StateFlow<Int> = _vibrateStrength.asStateFlow()

    private val _ringtoneKey = MutableStateFlow(settings.getString("notif_ringtone_key", "default"))
    val ringtoneKey: StateFlow<String> = _ringtoneKey.asStateFlow()

    init {
        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { s ->
                val strength = s.notificationVibrationStrength ?: 2
                _vibrateStrength.value = strength
                settings.putInt("notif_vibrate_strength", strength)
            }
        }
    }

    fun setVibrateStrength(strength: Int) {
        _vibrateStrength.value = strength
        settings.putInt("notif_vibrate_strength", strength)
        viewModelScope.launch {
            userRepository.updateSettings(
                UpdateSettingsRequest(notificationVibrationStrength = strength)
            )
        }
    }

    fun setRingtoneKey(key: String) {
        _ringtoneKey.value = key
        settings.putString("notif_ringtone_key", key)
    }

    companion object {
        val ringtoneKeys = listOf("default", "classic", "simple", "none")

        fun strengthToLabel(v: Int, s: AppStrings) = when (v) {
            0    -> s.vibrateNone
            1    -> s.vibrateWeak
            3    -> s.vibrateStrong
            else -> s.vibrateMedium
        }
        fun labelToStrength(label: String, s: AppStrings) = when (label) {
            s.vibrateNone   -> 0
            s.vibrateWeak   -> 1
            s.vibrateStrong -> 3
            else            -> 2
        }
        fun vibrateOptions(s: AppStrings) =
            listOf(s.vibrateNone, s.vibrateWeak, s.vibrateMedium, s.vibrateStrong)

        fun ringtoneKeyToLabel(key: String, s: AppStrings) = when (key) {
            "classic" -> s.ringtoneClassic
            "simple"  -> s.ringtoneSimple
            "none"    -> s.ringtoneNone
            else      -> s.ringtoneDefault
        }
        fun ringtoneLabelToKey(label: String, s: AppStrings) = when (label) {
            s.ringtoneClassic -> "classic"
            s.ringtoneSimple  -> "simple"
            s.ringtoneNone    -> "none"
            else              -> "default"
        }
        fun ringtoneOptions(s: AppStrings) =
            ringtoneKeys.map { ringtoneKeyToLabel(it, s) }
    }
}
