package com.example.memegram

import android.os.Build
import android.provider.Settings

actual fun getHardwareDeviceId(): String =
    Settings.Secure.getString(
        AppContextHolder.context.contentResolver,
        Settings.Secure.ANDROID_ID
    )

actual fun getDeviceModelName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return when {
        manufacturer.isBlank() && model.isBlank() -> "Android Device"
        manufacturer.isBlank() -> model
        model.isBlank() -> manufacturer.replaceFirstChar { it.uppercase() }
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
    }
}
