@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.example.memegram

import platform.UIKit.UIPasteboard

actual fun copyTextToClipboard(text: String) {
    UIPasteboard.generalPasteboard.string = text
}
