package com.example.memegram

/**
 * Platform-specific clipboard write.
 *
 * On iOS Compose Multiplatform's [androidx.compose.ui.platform.LocalClipboardManager.setText]
 * has been observed to hang the UI thread (see ProfileScreen public-key copy bug).
 * Using the platform pasteboard API directly avoids that path.
 */
expect fun copyTextToClipboard(text: String)
