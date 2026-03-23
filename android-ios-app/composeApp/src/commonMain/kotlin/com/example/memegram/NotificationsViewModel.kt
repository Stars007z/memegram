package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UpdateSettingsRequest
import com.example.memegram.data.repository.UserRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel(
    private val settings: Settings,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _vibrate  = MutableStateFlow(settings.getString("notif_vibrate",  "Средняя"))
    val vibrate:  StateFlow<String> = _vibrate.asStateFlow()
    private val _ringtone = MutableStateFlow(settings.getString("notif_ringtone", "Default"))
    val ringtone: StateFlow<String> = _ringtone.asStateFlow()

    init {
        viewModelScope.launch {
            userRepository.loadSettings().onSuccess { s ->
                val label = strengthToLabel(s.notificationVibrationStrength ?: 2)
                _vibrate.value = label
                settings.putString("notif_vibrate", label)
            }
        }
    }

    fun setVibrate(label: String) {
        _vibrate.value = label
        settings.putString("notif_vibrate", label)
        viewModelScope.launch {
            userRepository.updateSettings(
                UpdateSettingsRequest(notificationVibrationStrength = labelToStrength(label))
            )
        }
    }

    fun setRingtone(label: String) {
        _ringtone.value = label
        settings.putString("notif_ringtone", label)
    }

    companion object {
        fun strengthToLabel(v: Int) = when (v) {
            0    -> "Нет"
            1    -> "Слабая"
            3    -> "Сильная"
            else -> "Средняя"
        }
        fun labelToStrength(label: String) = when (label) {
            "Нет"     -> 0
            "Слабая"  -> 1
            "Сильная" -> 3
            else      -> 2
        }
        val vibrateOptions  = listOf("Нет", "Слабая", "Средняя", "Сильная")
        val ringtoneOptions = listOf("Default", "Классический", "Простой", "Нет")
    }
}