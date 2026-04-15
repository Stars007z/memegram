package com.example.memegram.translation

/**
 * iOS stub — returns "not available" strings.
 * CoreML wiring is left as future work.
 */
actual class TranslationManager {

    actual suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String = "⚠️ Translation not available on iOS"

    actual suspend fun transcribeAudio(audioBytes: ByteArray): String =
        "⚠️ Transcription not available on iOS"

    actual fun isTranslationAvailable(): Boolean = false
    actual fun isTranscriptionAvailable(): Boolean = false
    actual fun release() {}
}
