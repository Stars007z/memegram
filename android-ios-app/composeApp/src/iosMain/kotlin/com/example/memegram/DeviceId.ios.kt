package com.example.memegram

import platform.UIKit.UIDevice

actual fun getHardwareDeviceId(): String =
    UIDevice.currentDevice.identifierForVendor?.UUIDString
        ?: "unknown-ios-device"

actual fun getDeviceModelName(): String {
    val name = UIDevice.currentDevice.name.trim()
    val model = UIDevice.currentDevice.model.trim()
    return when {
        name.isNotBlank() -> name
        model.isNotBlank() -> model
        else -> "iOS Device"
    }
}
