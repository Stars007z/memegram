package com.example.memegram.data.network

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ApiException(
    val status: HttpStatusCode,
    val code: String?,
    val detail: String,
    val rawBody: String
) : RuntimeException(buildMessage(status, code, detail)) {

    val isBlocked: Boolean
        get() = status == HttpStatusCode.Forbidden &&
                (code == "PERMISSION_DENIED" || code == "BLOCKED" ||
                 detail.contains("block", ignoreCase = true))

    val isRecipientUnavailable: Boolean
        get() = status == HttpStatusCode.Forbidden &&
                (code == "RECIPIENT_UNAVAILABLE" ||
                 code == "ACCOUNT_DELETED" ||
                 detail.contains("recipient is not available", ignoreCase = true) ||
                 detail.contains("recipient unavailable", ignoreCase = true) ||
                 detail.contains("account deleted", ignoreCase = true))

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun from(status: HttpStatusCode, body: String): ApiException {
            val parsed = parseBody(body)
            return ApiException(
                status = status,
                code = parsed.code,
                detail = parsed.detail,
                rawBody = body
            )
        }

        private fun parseBody(body: String): ParsedError {
            if (body.isBlank()) return ParsedError(null, "")
            return runCatching {
                val env = json.decodeFromString<ErrorEnvelope>(body)
                val rawDetail = env.detail ?: env.message ?: body
                val (c, d) = splitCodeAndDetail(rawDetail)
                ParsedError(env.code ?: c, d)
            }.getOrElse {
                val (c, d) = splitCodeAndDetail(body)
                ParsedError(c, d)
            }
        }

        private fun splitCodeAndDetail(s: String): Pair<String?, String> {
            val idx = s.indexOf(':')
            if (idx <= 0) return null to s.trim()
            val head = s.substring(0, idx).trim()
            val tail = s.substring(idx + 1).trim()
            val looksLikeCode = head.isNotEmpty() && head.all { it.isUpperCase() || it == '_' || it.isDigit() }
            return if (looksLikeCode) head to tail else null to s.trim()
        }

        private fun buildMessage(status: HttpStatusCode, code: String?, detail: String): String {
            val codePart = code?.let { " [$it]" } ?: ""
            val detailPart = if (detail.isNotBlank()) ": $detail" else ""
            return "HTTP ${status.value}$codePart$detailPart"
        }
    }

    @Serializable
    private data class ErrorEnvelope(
        val detail: String? = null,
        val message: String? = null,
        val code: String? = null,
        @SerialName("error") val error: String? = null
    )

    private data class ParsedError(val code: String?, val detail: String)
}
