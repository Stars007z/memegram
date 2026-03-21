package com.example.voicetranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WhisperLocal(private val context: Context) {
    
    companion object {
        private const val TAG = "WhisperLocal"
        private const val MODEL_NAME = "ggml-small-q5_1.bin"
        
        init {
            System.loadLibrary("whisperjni")
        }
    }
    
    private var isInitialized = false
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelFile = copyModelFromAssets()
            isInitialized = initModel(modelFile.absolutePath)
            Log.d(TAG, "Model initialized: $isInitialized")
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            false
        }
    }
    
    private fun copyModelFromAssets(): File {
        val outFile = File(context.filesDir, MODEL_NAME)
        if (!outFile.exists()) {
            context.assets.open(MODEL_NAME).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model copied to: ${outFile.absolutePath}")
        }
        return outFile
    }
    
    suspend fun transcribe(audioFilePath: String, language: String = "auto"): String = 
        withContext(Dispatchers.IO) {
            if (!isInitialized) {
                Log.e(TAG, "Model not initialized!")
                return@withContext "ERROR: Model not initialized"
            }
            transcribeFile(audioFilePath, language)
        }
    
    fun release() {
        releaseModel()
        isInitialized = false
    }
    
    private external fun initModel(modelPath: String): Boolean
    private external fun transcribeFile(audioPath: String, language: String): String
    private external fun releaseModel()
}
