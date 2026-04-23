package com.example.memegram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

actual fun copyTextToClipboard(text: String) {
    val ctx = AppContextHolder.context
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
}
