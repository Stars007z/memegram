package com.example.memegram.transcription

import android.content.Context
import android.util.Log
import com.example.memegram.ml.MlModelGate
import com.example.memegram.translation.ModelDownloadProgress
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class WhisperTranscriptionService(
    private val context: Context,
    httpClient: HttpClient,
    modelBaseUrl: String,
) : TranscriptionService {

    companion object {
        private const val TAG = "Whisper"
    }

    private val modelManager = WhisperModelManager(context, httpClient, modelBaseUrl)

    override suspend fun transcribe(
        audioBytes: ByteArray,
        language: String?,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "┌── transcribe() START ──────────────────────")
        Log.d(TAG, "│ audioBytes.size=${audioBytes.size}, language=$language")

        val tLoad = System.currentTimeMillis()
        val engine = modelManager.getEngine()
        val loadMs = System.currentTimeMillis() - tLoad
        if (engine == null) {
            Log.e(TAG, "└── FAIL: getEngine() returned null after ${loadMs}ms (low RAM or model missing)")
            return TranscriptionResult("", "")
        }
        Log.d(TAG, "│ Model ready in ${loadMs}ms")

        val callback = object : uniffi.mls_core.WhisperProgressCallback {
            override fun onProgress(progress: Int) {
                onProgress(TranscriptionProgress(progress / 100f))
            }
        }

        return try {
            Log.d(TAG, "│ Running inference...")
            val tInf = System.currentTimeMillis()
            val result = engine.transcribe(audioBytes, language, callback)
            val infMs = System.currentTimeMillis() - tInf
            Log.d(TAG, "│ Inference done in ${infMs}ms")
            Log.d(TAG, "│ result.language=${result.language}, text='${result.text.take(80)}'")
            Log.d(TAG, "└── OK: transcribed in ${System.currentTimeMillis() - t0}ms total")
            TranscriptionResult(text = result.text, language = result.language)
        } catch (e: Throwable) {
            Log.e(TAG, "└── ERROR in inference: ${e::class.simpleName}: ${e.message}", e)
            TranscriptionResult("", "")
        }
    }

    override suspend fun ensureModelReady() {
        val engine = modelManager.getEngine()
        Log.d(TAG, "ensureModelReady(): engineReady=${engine != null}")
    }

    override suspend fun releaseModel() {
        modelManager.release()
        Log.d(TAG, "releaseModel(): released (gate hook)")
    }

    override fun isModelAvailable(): Boolean = modelManager.isModelAvailable()

    override fun getModelSize(): Long = modelManager.getModelSize()

    override fun downloadModel(): Flow<ModelDownloadProgress> = modelManager.downloadModel()

    override suspend fun deleteModel() = MlModelGate.withExclusiveModelAccess {
        modelManager.deleteModel()
    }
}
