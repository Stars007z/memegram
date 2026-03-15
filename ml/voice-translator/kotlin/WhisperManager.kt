package com.example.voicetranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class WhisperManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WhisperManager"
    }
    
    enum class Mode {
        ON_DEVICE,
        API
    }
    
    private val _state = MutableStateFlow(WhisperState.Idle)
    val state: StateFlow<WhisperState> = _state
    
    private var localWhisper: WhisperLocal? = null
    private var apiWhisper: WhisperApi? = null
    private var currentMode: Mode = Mode.ON_DEVICE
    
    sealed class WhisperState {
        object Idle : WhisperState()
        object Loading : WhisperState()
        data class Processing(val progress: Int) : WhisperState()
        data class Success(val result: TranslationResult) : WhisperState()
        data class Error(val message: String) : WhisperState()
    }
    
    suspend fun initialize(mode: Mode, apiKey: String? = null) {
        _state.value = WhisperState.Loading
        currentMode = mode
        
        try {
            when (mode) {
                Mode.ON_DEVICE -> {
                    localWhisper = WhisperLocal(context)
                    val success = localWhisper?.initialize() == true
                    _state.value = if (success) {
                        WhisperState.Idle
                    } else {
                        WhisperState.Error("Failed to load local model")
                    }
                }
                Mode.API -> {
                    if (apiKey.isNullOrBlank()) {
                        _state.value = WhisperState.Error("API key required")
                        return
                    }
                    apiWhisper = WhisperApi(apiKey)
                    _state.value = WhisperState.Idle
                }
            }
        } catch (e: Exception) {
            _state.value = WhisperState.Error(e.message ?: "Unknown error")
        }
    }
    
    suspend fun translateVoiceMessage(
        audioFile: File,
        sourceLanguage: String = "auto",
        targetLanguage: String = "ru"
    ) {
        _state.value = WhisperState.Processing(10)
        
        try {
            val startTime = System.currentTimeMillis()
            
            _state.value = WhisperState.Processing(30)
            val transcribedText = when (currentMode) {
                Mode.ON_DEVICE -> localWhisper?.transcribe(audioFile.path, sourceLanguage) ?: ""
                Mode.API -> apiWhisper?.transcribe(audioFile, sourceLanguage)?.text ?: ""
            }
            
            _state.value = WhisperState.Processing(60)
            
            if (transcribedText.isBlank() || transcribedText.startsWith("ERROR")) {
                throw Exception("Transcription failed: $transcribedText")
            }
            
            _state.value = WhisperState.Processing(80)
            val translatedText = if (currentMode == Mode.API) {
                // API может сразу вернуть русский, если указать language="ru"
                transcribedText
            } else {
                // Для On-Device нужен отдельный перевод
                apiWhisper?.translate(transcribedText, targetLanguage) ?: transcribedText
            }
            
            _state.value = WhisperState.Processing(100)
            
            val duration = System.currentTimeMillis() - startTime
            
            val result = TranslationResult(
                originalText = transcribedText,
                translatedText = translatedText,
                language = sourceLanguage,
                durationMs = duration,
                mode = currentMode,
                success = true
            )
            
            _state.value = WhisperState.Success(result)
            Log.d(TAG, "Translation complete in ${duration}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}")
            _state.value = WhisperState.Error(e.message ?: "Unknown error")
        }
    }
    
    fun release() {
        localWhisper?.release()
        localWhisper = null
        apiWhisper = null
        _state.value = WhisperState.Idle
    }
}
