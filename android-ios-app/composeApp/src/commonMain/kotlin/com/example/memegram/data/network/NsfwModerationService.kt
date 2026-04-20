package com.example.memegram.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.util.decodeBase64Bytes

/**
 * Result of NSFW moderation.
 *
 * @param wasBlurred true if the service applied partial blur to the image
 * @param processedBytes image bytes to upload (either original if safe, or blurred PNG)
 * @param processedMimeType mime-type of [processedBytes]
 * @param predictedClass top-1 class from the classifier (for logging/UI)
 */
data class NsfwModerationResult(
    val wasBlurred: Boolean,
    val processedBytes: ByteArray,
    val processedMimeType: String,
    val predictedClass: String?,
)

/**
 * Client for the standalone NSFW moderation FastAPI service
 * (ml/nsfw/content_moderation_test/app.py).
 *
 * The service applies *partial blur* to detected nudity / adult-content regions
 * (NudeNet bboxes + sliding window for non-nudity classes) and returns the
 * resulting PNG as base64. We swap the original image for the blurred one
 * before E2E-encrypting and uploading it.
 */
class NsfwModerationService(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * POST /api/moderate (multipart/form-data: file, threshold).
     *
     * Returns processed bytes + flag. On any failure (network, 5xx, malformed
     * response) returns the original bytes and `wasBlurred = false` — the upload
     * pipeline must keep working even if the moderation service is down.
     */
    suspend fun moderate(
        imageBytes: ByteArray,
        mimeType: String,
        threshold: Float = 1.8f,
        filename: String = "image",
    ): NsfwModerationResult {
        return try {
            val response = client.post("$baseUrl/api/moderate") {
                timeout {
                    requestTimeoutMillis = 60_000
                    socketTimeoutMillis = 60_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                imageBytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, mimeType.ifBlank { "image/jpeg" })
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"$filename\""
                                    )
                                },
                            )
                            append("threshold", threshold.toString())
                        }
                    )
                )
            }
            if (!response.status.isSuccess()) {
                println("MemegramDebug [NSFW] non-2xx ${response.status.value}: ${response.bodyAsText().take(200)}")
                return NsfwModerationResult(false, imageBytes, mimeType, null)
            }
            val bodyText = response.bodyAsText()
            val root: JsonObject = json.parseToJsonElement(bodyText).jsonObject
            val predicted = root["predicted_class"]?.jsonPrimitive?.contentOrNull
            val patches = root["patch_results"]?.jsonObject
            val blurred = patches?.get("blurred")?.jsonPrimitive?.boolean == true
            val action = root["action_taken"]?.jsonPrimitive?.contentOrNull
            val needsReplacement = blurred || action == "partial_blur"
            if (!needsReplacement) {
                return NsfwModerationResult(false, imageBytes, mimeType, predicted)
            }
            val processedDataUrl = root["processed_image"]?.jsonPrimitive?.contentOrNull
            if (processedDataUrl.isNullOrBlank()) {
                return NsfwModerationResult(false, imageBytes, mimeType, predicted)
            }
            // Format: "data:image/png;base64,<...>"
            val commaIdx = processedDataUrl.indexOf(',')
            if (commaIdx < 0) {
                return NsfwModerationResult(false, imageBytes, mimeType, predicted)
            }
            val b64 = processedDataUrl.substring(commaIdx + 1)
            val processedBytes = b64.decodeBase64Bytes()
            val header = processedDataUrl.substring(0, commaIdx)
            val processedMime = Regex("data:([^;]+);").find(header)?.groupValues?.getOrNull(1) ?: "image/png"
            println("MemegramDebug [NSFW] blurred=true class=$predicted, replaced ${imageBytes.size}B → ${processedBytes.size}B")
            NsfwModerationResult(true, processedBytes, processedMime, predicted)
        } catch (e: Exception) {
            println("MemegramDebug [NSFW] ⚠ skipped due to error: ${e::class.simpleName}: ${e.message}")
            NsfwModerationResult(false, imageBytes, mimeType, null)
        }
    }
}
