package com.example.memegram.translation

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

data class TranslationResult(
    val translatedText: String,
    val detectedSourceLang: String
)

enum class TranslationProgressPhase {
    IDENTIFYING_LANGUAGE,
    LOADING_MODEL,
    TOKENIZING,
    ENCODING,
    LOADING_DECODER,
    DECODING,
    FINISHING
}

data class TranslationProgress(
    val fraction: Float,
    val phase: TranslationProgressPhase,
    val completedSentences: Int = 0,
    val totalSentences: Int = 0,
)

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
        targetLang: String,
        onProgress: (TranslationProgress) -> Unit = {}
    ): TranslationResult

    suspend fun identifyLanguage(text: String): String?

    suspend fun ensureModelReady(langCode: String)

    fun close()

    fun isModelAvailable(): Boolean

    fun getModelSize(): Long

    fun downloadModel(): Flow<ModelDownloadProgress>

    suspend fun deleteModel()
    suspend fun releaseModel()
}

expect fun createTranslationService(
    httpClient: HttpClient,
    modelBaseUrl: String
): TranslationService
