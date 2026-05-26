package com.example.memegram.transcription

import com.example.memegram.translation.ModelDownloadProgress
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

data class TranscriptionResult(
    val text: String,
    val language: String,
)

data class TranscriptionProgress(
    val fraction: Float,
)

interface TranscriptionService {

    suspend fun transcribe(
        audioBytes: ByteArray,
        language: String? = null,
        onProgress: (TranscriptionProgress) -> Unit = {},
    ): TranscriptionResult

    suspend fun ensureModelReady()

    suspend fun releaseModel()

    fun isModelAvailable(): Boolean

    fun getModelSize(): Long

    fun downloadModel(): Flow<ModelDownloadProgress>

    suspend fun deleteModel()
}

expect fun createTranscriptionService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): TranscriptionService
