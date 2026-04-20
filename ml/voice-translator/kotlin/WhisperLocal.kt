package com.example.voicetranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class WhisperLocal(private val context: Context) {

    companion object {
        private const val TAG = "WhisperLocal"
        private const val MODEL_NAME = "ggml-small-q5_1.bin"
        // Минимальный разумный размер для любой ggml-whisper модели (tiny ~30 MB).
        private const val MIN_MODEL_SIZE_BYTES = 20L * 1024L * 1024L

        init {
            System.loadLibrary("whisperjni")
        }
    }

    @Volatile
    private var isInitialized = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelFile = ensureModel()
            val ok = initModel(modelFile.absolutePath)
            isInitialized = ok
            Log.d(TAG, "Model initialized: $ok (size=${modelFile.length()} bytes)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            false
        }
    }

    /**
     * Копирует модель из assets → filesDir атомарно (сначала во временный файл,
     * затем rename). Если существующий файл битый / маленький — перезаписывает.
     */
    private fun ensureModel(): File {
        val out = File(context.filesDir, MODEL_NAME)

        if (out.exists() && out.length() >= MIN_MODEL_SIZE_BYTES) {
            return out
        }
        if (out.exists()) {
            Log.w(TAG, "Existing model invalid (size=${out.length()}), re-copying")
            out.delete()
        }

        val tmp = File(context.filesDir, "$MODEL_NAME.part")
        tmp.delete()
        try {
            context.assets.open(MODEL_NAME).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() < MIN_MODEL_SIZE_BYTES) {
                throw IOException("Copied model too small: ${tmp.length()} bytes")
            }
            if (!tmp.renameTo(out)) {
                throw IOException("Failed to rename ${tmp.name} → ${out.name}")
            }
            Log.d(TAG, "Model copied to: ${out.absolutePath}")
            return out
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    suspend fun transcribe(audioFilePath: String, language: String = "auto"): String =
        withContext(Dispatchers.IO) {
            if (!isInitialized) {
                Log.e(TAG, "Model not initialized!")
                return@withContext "ERROR: Model not initialized"
            }
            val file = File(audioFilePath)
            if (!file.exists() || file.length() == 0L) {
                return@withContext "ERROR: Audio file missing or empty"
            }
            transcribeFile(audioFilePath, language)
        }

    fun release() {
        if (isInitialized) {
            releaseModel()
            isInitialized = false
        }
    }

    private external fun initModel(modelPath: String): Boolean
    private external fun transcribeFile(audioPath: String, language: String): String
    private external fun releaseModel()
}
