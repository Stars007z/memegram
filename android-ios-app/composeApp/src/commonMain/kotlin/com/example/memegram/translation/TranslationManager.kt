package com.example.memegram.translation

/**
 * Multiplatform expect declaration for on-device translation (Helsinki-NLP MarianMT ONNX)
 * and voice transcription (Whisper ONNX).
 *
 * Android actual: OnnxRuntime sessions reading encoder_model.onnx / decoder_model.onnx
 *                 from assets/translation/ and assets/whisper/.
 *                 Constructor receives Android Context — registered via platformModule().
 * iOS actual:     Stub returning "not available" strings; CoreML wiring left as future work.
 *                 No-arg constructor.
 */
expect class TranslationManager {
    /**
     * Translates [text] from [sourceLang] to [targetLang].
     * Pass "auto" for [sourceLang] to let the model infer the source language.
     * Returns the translated string, or an error string prefixed with "⚠️".
     */
    suspend fun translate(
        text: String,
        sourceLang: String = "auto",
        targetLang: String = "ru"
    ): String

    /**
     * Transcribes raw audio bytes (M4A / AAC container expected on Android) using Whisper.
     * Returns the transcribed text, or an error string prefixed with "⚠️".
     */
    suspend fun transcribeAudio(audioBytes: ByteArray): String

    /** Returns true when the translation ONNX sessions are loaded and ready. */
    fun isTranslationAvailable(): Boolean

    /** Returns true when the Whisper ONNX model files are present in assets. */
    fun isTranscriptionAvailable(): Boolean

    /** Releases all native ONNX Runtime resources. Call from onCleared() / lifecycle stop. */
    fun release()
}
