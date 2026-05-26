package com.example.memegram

import com.example.memegram.localization.S
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ParsedMlsPayload(
    val type: String,
    val mediaId: String,
    val content: String,
    val groupId: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val fileMime: String? = null,
)

@OptIn(ExperimentalEncodingApi::class)
fun parseMlsPayload(payload: String): ParsedMlsPayload {
    if (payload.startsWith("[image:")) {
        val closeIdx = payload.indexOf(']')
        if (closeIdx == -1) return ParsedMlsPayload("text", "", payload)
        val metaInfo = payload.substring(7, closeIdx).split(":")
        val mediaId = metaInfo[0]
        val groupId = if (metaInfo.size > 1) metaInfo[1] else null
        val caption = payload.substring(closeIdx + 1).trim()
        return ParsedMlsPayload("image", mediaId, caption, groupId)
    }
    if (payload.startsWith("[voice:")) {
        val closeIdx = payload.indexOf(']')
        if (closeIdx == -1) return ParsedMlsPayload("text", "", payload)

        val metaInfo = payload.substring(7, closeIdx).split(":")
        val mediaId = metaInfo[0]
        val waveform = if (metaInfo.size > 1) metaInfo[1] else ""
        val durationMs = payload.substring(closeIdx + 1).trim()

        return ParsedMlsPayload("voice", mediaId, "$durationMs|$waveform")
    }
    if (payload.startsWith("[file:")) {
        val closeIdx = payload.indexOf(']')
        if (closeIdx == -1) return ParsedMlsPayload("text", "", payload)
        val metaInfo = payload.substring(6, closeIdx).split(":")
        if (metaInfo.size < 3) return ParsedMlsPayload("text", "", payload)
        val mediaId = metaInfo[0]
        val sizeBytes = metaInfo[1].toLongOrNull() ?: 0L
        val mime = runCatching { decodeBase64Utf8(metaInfo[2]) }.getOrDefault("application/octet-stream")
        val tail = payload.substring(closeIdx + 1)
        val parts = tail.split(":", limit = 2)
        val fileName = runCatching { decodeBase64Utf8(parts[0]) }.getOrDefault(parts[0])
        val caption = if (parts.size > 1) runCatching { decodeBase64Utf8(parts[1]) }.getOrDefault("") else ""
        return ParsedMlsPayload(
            type = "file",
            mediaId = mediaId,
            content = caption,
            fileName = fileName,
            fileSize = sizeBytes,
            fileMime = mime,
        )
    }
    return ParsedMlsPayload("text", "", payload)
}

fun messagePreviewText(message: Message): String {
    val parsedRawPayload = if (message.type == "text") parseMlsPayload(message.text) else null
    val parsedMediaPayload = parsedRawPayload?.takeIf { it.type != "text" }
    val type = parsedMediaPayload?.type ?: message.type
    val text = parsedMediaPayload?.content ?: message.text
    val fileName = parsedMediaPayload?.fileName ?: message.fileName

    return when {
        type == "voice" -> "🎤 ${S.current.voiceMessage}"
        type == "file" -> if (text.isNotBlank()) text else "📎 ${fileName ?: S.current.file}"
        type == "image" && text.isBlank() -> "📸 ${S.current.photo}"
        type == "image" -> text
        text.isNotBlank() -> text
        else -> S.current.messageDeleted
    }
}

fun parsedPayloadPreviewText(parsed: ParsedMlsPayload): String = when {
    parsed.type == "voice" -> "🎤 ${S.current.voiceMessage}"
    parsed.type == "file" -> if (parsed.content.isNotBlank()) parsed.content else "📎 ${parsed.fileName ?: S.current.file}"
    parsed.type == "image" && parsed.content.isBlank() -> "📸 ${S.current.photo}"
    parsed.type == "image" -> parsed.content
    parsed.content.isNotBlank() -> parsed.content
    else -> S.current.messageDeleted
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64Utf8(s: String): String =
    Base64.decode(s).decodeToString()
