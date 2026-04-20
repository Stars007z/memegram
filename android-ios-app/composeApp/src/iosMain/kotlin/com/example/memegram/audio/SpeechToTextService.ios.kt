package com.example.memegram.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS stub. On-device STT на iOS пока не поддержан.
 * Возвращаем implementation с `isSupported = false`, чтобы UI мог скрыть кнопку,
 * а случайный вызов бросит понятное исключение.
 */
private class IosSpeechToTextServiceStub : SpeechToTextService {
    override val isSupported: Boolean = false

    override val modelDownloadState: StateFlow<ModelDownloadState> =
        MutableStateFlow(ModelDownloadState.Idle)

    override suspend fun transcribe(
        audioBytes: ByteArray,
        mimeType: String?,
        hintLanguage: String?
    ): TranscriptionResult {
        throw UnsupportedOperationException(
            "Speech-to-text is not available on iOS yet."
        )
    }

    override fun close() { /* no-op */ }
}

actual fun createSpeechToTextService(): SpeechToTextService = IosSpeechToTextServiceStub()
