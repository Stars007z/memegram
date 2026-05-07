package com.example.memegram.transcription

import com.example.memegram.translation.ModelDownloadProgress
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class IosWhisperTranscriptionService(
    httpClient: HttpClient,
    modelBaseUrl: String,
) : TranscriptionService {

    private val modelManager = IosWhisperModelManager(httpClient, modelBaseUrl)

    override suspend fun transcribe(
        audioBytes: ByteArray,
        language: String?,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult {
        println("[Whisper-iOS] transcribe() START audioBytes=${audioBytes.size} language=$language")

        val engine = modelManager.getEngine()
        if (engine == null) {
            println("[Whisper-iOS] transcribe(): engine null (model missing or low RAM)")
            return TranscriptionResult("", "")
        }

        val callback = object : uniffi.mls_core.WhisperProgressCallback {
            override fun onProgress(progress: Int) {
                onProgress(TranscriptionProgress(progress / 100f))
            }
        }

        return try {
            val result = engine.transcribe(audioBytes, language, callback)
            println("[Whisper-iOS] transcribe(): OK lang=${result.language} text='${result.text.take(80)}'")
            TranscriptionResult(text = result.text, language = result.language)
        } catch (e: Throwable) {
            println("[Whisper-iOS] transcribe(): ERROR ${e::class.simpleName}: ${e.message}")
            TranscriptionResult("", "")
        }
    }

    override suspend fun ensureModelReady() {
        modelManager.isModelAvailable()
    }

    override suspend fun releaseModel() {
        modelManager.release()
        println("[Whisper-iOS] releaseModel(): released (gate hook)")
    }

    override fun isModelAvailable(): Boolean = modelManager.isModelAvailable()
    override fun getModelSize(): Long = modelManager.getModelSize()
    override fun downloadModel(): Flow<ModelDownloadProgress> = modelManager.downloadModel()
    override suspend fun deleteModel() = modelManager.deleteModel()
}
