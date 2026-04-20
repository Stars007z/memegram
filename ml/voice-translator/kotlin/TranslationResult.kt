package com.example.voicetranslator

/**
 * Единый enum для режима работы транскрибера — используется и в WhisperManager,
 * и в TranslationResult, чтобы не было type mismatch.
 */
enum class WhisperMode {
    ON_DEVICE,
    API
}

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val language: String,
    val durationMs: Long,
    val mode: WhisperMode,
    val success: Boolean,
    val error: String? = null
)
