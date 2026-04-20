package com.example.memegram.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Результат транскрибации войс-сообщения.
 *
 * @param text       распознанный текст на исходном языке.
 * @param language   ISO-код обнаруженного языка (например "ru", "en").
 *                   Может быть `"auto"` если движок не смог определить.
 */
data class TranscriptionResult(
    val text: String,
    val language: String
)

/**
 * Состояние скачивания нативной модели (whisper ggml ~470 MB).
 *
 * UI должен отображать панель с прогрессом во время [Downloading], когда юзер
 * первый раз нажимает «транскрибировать».
 */
sealed class ModelDownloadState {
    /** Файл модели уже на устройстве, скачивать ничего не нужно. */
    object Ready : ModelDownloadState()

    /** Идёт загрузка. */
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : ModelDownloadState() {
        /** 0.0..1.0 или null если размер неизвестен. */
        val fraction: Float?
            get() = if (totalBytes > 0) (bytesDownloaded.toDouble() / totalBytes).toFloat()
                    else null
    }

    /** Загрузка завершилась с ошибкой; пользователь может ретраить. */
    data class Failed(val message: String) : ModelDownloadState()

    /** Ещё не начинали (initial state). */
    object Idle : ModelDownloadState()
}

/**
 * Offline speech-to-text для войс-сообщений. Реализация платформо-зависимая
 * (Android — whisper.cpp, iOS — пока stub).
 */
interface SpeechToTextService {

    /** `true` если на этой платформе транскрибация поддерживается. */
    val isSupported: Boolean

    /**
     * Текущее состояние загрузки модели. UI подписывается чтобы показать прогресс
     * первого скачивания (~470 MB).
     */
    val modelDownloadState: StateFlow<ModelDownloadState>

    /**
     * Транскрибирует уже расшифрованные байты войса (m4a/ogg/wav).
     *
     * @param audioBytes     содержимое аудио-файла как пришло с S3 (после MLS-расшифровки).
     * @param mimeType       MIME-тип ("audio/mp4", "audio/ogg" и т.п.). Если null — угадываем.
     * @param hintLanguage   ISO-код подсказка ("ru"), или `null`/`"auto"` для автодетекта.
     */
    suspend fun transcribe(
        audioBytes: ByteArray,
        mimeType: String? = null,
        hintLanguage: String? = null
    ): TranscriptionResult

    /** Освобождает ресурсы (нативный контекст, временные файлы). */
    fun close()
}

expect fun createSpeechToTextService(): SpeechToTextService
