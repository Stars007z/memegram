package com.example.memegram.nsfw

import com.example.memegram.ml.MlModelGate
import com.example.memegram.translation.ModelDownloadProgress
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

actual fun createNsfwService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): NsfwService = IosNsfwService(httpClient, modelBaseUrl)

private class IosNsfwService(
    httpClient: HttpClient,
    modelBaseUrl: String,
) : NsfwService {

    private val modelManager = NsfwModelManager(httpClient, modelBaseUrl)

    override suspend fun censorImageIfNeeded(
        imageBytes: ByteArray,
        mime: String?,
    ): NsfwCensorResult {
        val engine = modelManager.getEngine()
            ?: return NsfwCensorResult(imageBytes, processed = false)
        return try {
            engine.censorImageIfNeeded(imageBytes, mime)
        } finally {
            modelManager.release()
        }
    }

    override fun isSupported(): Boolean = true

    override fun isModelAvailable(): Boolean = modelManager.isModelAvailable()

    override fun getModelSize(): Long = modelManager.getModelSize()

    override fun downloadModel(): Flow<ModelDownloadProgress> = modelManager.downloadModel()

    override suspend fun deleteModel() = MlModelGate.withExclusiveModelAccess {
        modelManager.deleteModel()
    }

    override suspend fun releaseModel() {
        modelManager.release()
    }
}
