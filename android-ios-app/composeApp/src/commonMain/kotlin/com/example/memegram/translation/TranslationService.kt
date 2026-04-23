package com.example.memegram.translation

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

data class TranslationResult(
    val translatedText: String,
    val detectedSourceLang: String
)

/**
 * Progress of NLLB-200 model download.
 *
 * @param bytesDownloaded bytes received so far
 * @param totalBytes total expected bytes (-1 if unknown / server didn't send Content-Length)
 */
data class ModelDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toDouble() / totalBytes).toFloat() else 0f
}

interface TranslationService {
    suspend fun translate(
        text: String,
        sourceLang: String? = null,
        targetLang: String
    ): TranslationResult

    suspend fun identifyLanguage(text: String): String?

    suspend fun ensureModelReady(langCode: String)

    fun close()

    /** Whether the NLLB model files are present locally and ready to use. */
    fun isModelAvailable(): Boolean

    /** Approximate on-disk size of the model in bytes (0 if not downloaded). */
    fun getModelSize(): Long

    /**
     * Download the NLLB-200 model from the configured remote storage
     * (Cloudflare R2). Emits progress; throws on network or extraction error.
     * If the model is already complete, the flow completes immediately.
     */
    fun downloadModel(): Flow<ModelDownloadProgress>

    /** Delete the model from local storage to free space. */
    suspend fun deleteModel()
}

/**
 * Platform factory.
 *
 * @param httpClient shared Ktor client (Koin-provided)
 * @param modelBaseUrl base URL of the model bucket, e.g.
 *   "https://models.memegram.win" — the implementation appends
 *   "/nllb-200-distilled-600M.zip".
 */
expect fun createTranslationService(
    httpClient: HttpClient,
    modelBaseUrl: String
): TranslationService
