package com.example.memegram.nsfw

import com.example.memegram.translation.ModelDownloadProgress
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

data class NsfwCensorResult(
    val bytes: ByteArray,
    val processed: Boolean,
)

interface NsfwService {
    suspend fun censorImageIfNeeded(
        imageBytes: ByteArray,
        mime: String? = null,
    ): NsfwCensorResult

    fun isSupported(): Boolean

    fun isModelAvailable(): Boolean

    fun getModelSize(): Long

    fun downloadModel(): Flow<ModelDownloadProgress>

    suspend fun deleteModel()

    suspend fun releaseModel()
}

expect fun createNsfwService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): NsfwService
