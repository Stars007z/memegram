package com.example.memegram

import platform.UIKit.UIDevice

actual fun getHardwareDeviceId(): String =
    UIDevice.currentDevice.identifierForVendor?.UUIDString
        ?: "unknown-ios-device"