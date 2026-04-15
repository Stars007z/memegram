package com.example.memegram

import android.provider.Settings

actual fun getHardwareDeviceId(): String =
    Settings.Secure.getString(
        AppContextHolder.context.contentResolver,
        Settings.Secure.ANDROID_ID
    )