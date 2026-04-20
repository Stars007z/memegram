package com.example.voicetranslator

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WhisperManager(private val context: Context) {

    companion object {
        private const val TAG = "WhisperManager"
    }

    private val _state = MutableStateFlow<WhisperState>(WhisperState.Idle)
    val state: StateFlow<WhisperState> = _state.asStateFlow()

    private val pipelineMutex = Mutex() // защита от параллельных вызовов translateVoiceMessage

    private var localWhisper: WhisperLocal? = null
    private var apiWhisper: WhisperApi? = null
    private var currentMode: WhisperMode = WhisperMode.ON_DEVICE

    // Кэш ML Kit-переводчиков по паре языков.
    private val translatorCache = mutableMapOf<String, Translator>()

    sealed class WhisperState {
        object Idle : WhisperState()
        object Loading : WhisperState()
        data class Processing(val progress: Int) : WhisperState()
        data class Success(val result: TranslationResult) : WhisperState()
        data class Error(val message: String) : WhisperState()
    }

    suspend fun initialize(mode: WhisperMode, apiKey: String? = null) {
        _state.value = WhisperState.Loading
        currentMode = mode

        try {
            when (mode) {
                WhisperMode.ON_DEVICE -> {
                    val local = WhisperLocal(context).also { localWhisper = it }
                    val ok = local.initialize()
                    _state.value = if (ok) WhisperState.Idle
                                   else WhisperState.Error("Failed to load local model")
                }
                WhisperMode.API -> {
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
    ) = pipelineMutex.withLock {
        _state.value = WhisperState.Processing(10)
        val startTime = System.currentTimeMillis()

        try {
            _state.value = WhisperState.Processing(30)

            // 1) Транскрибация
            val transcribed: String
            val detectedLang: String
            when (currentMode) {
                WhisperMode.ON_DEVICE -> {
                    val local = localWhisper
                        ?: throw IllegalStateException("Local whisper not initialized")
                    transcribed = local.transcribe(audioFile.path, sourceLanguage)
                    // whisper.cpp сам не возвращает язык в текстовом API —
                    // используем подсказку от пользователя либо авто==unknown.
                    detectedLang = sourceLanguage.takeIf { it.isNotBlank() && it != "auto" }
                        ?: "auto"
                }
                WhisperMode.API -> {
                    val api = apiWhisper
                        ?: throw IllegalStateException("API whisper not initialized")
                    val resp = api.transcribe(audioFile, sourceLanguage)
                    transcribed = resp.text
                    detectedLang = resp.detectedLanguage
                        ?: sourceLanguage.takeIf { it != "auto" } ?: "auto"
                }
            }

            if (transcribed.isBlank() || transcribed.startsWith("ERROR")) {
                throw Exception("Transcription failed: $transcribed")
            }

            _state.value = WhisperState.Processing(70)

            // 2) Перевод
            val translated = if (detectedLang == targetLanguage) {
                transcribed
            } else when (currentMode) {
                WhisperMode.ON_DEVICE ->
                    translateOnDevice(transcribed, detectedLang, targetLanguage)
                WhisperMode.API ->
                    apiWhisper?.translate(
                        transcribed,
                        sourceLang = detectedLang.takeIf { it != "auto" } ?: "en",
                        targetLang = targetLanguage
                    ) ?: transcribed
            }

            _state.value = WhisperState.Processing(100)

            val result = TranslationResult(
                originalText = transcribed,
                translatedText = translated,
                language = detectedLang,
                durationMs = System.currentTimeMillis() - startTime,
                mode = currentMode,
                success = true
            )
            _state.value = WhisperState.Success(result)
            Log.d(TAG, "Translation complete in ${result.durationMs} ms")
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}", e)
            _state.value = WhisperState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Полностью локальный перевод через ML Kit. Модель скачивается один раз
     * на пару языков (требуется Wi-Fi при первом вызове).
     */
    private suspend fun translateOnDevice(
        text: String, source: String, target: String
    ): String {
        val src = TranslateLanguage.fromLanguageTag(normalizeLang(source))
            ?: return text.also { Log.w(TAG, "ML Kit: unsupported source '$source'") }
        val dst = TranslateLanguage.fromLanguageTag(normalizeLang(target))
            ?: return text.also { Log.w(TAG, "ML Kit: unsupported target '$target'") }

        val key = "$src->$dst"
        val translator = translatorCache.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(dst)
                    .build()
            )
        }

        // Скачиваем модель при первом использовании (Wi-Fi only по умолчанию)
        suspendCancellableCoroutine<Unit> { cont ->
            translator.downloadModelIfNeeded(
                DownloadConditions.Builder().requireWifi().build()
            )
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /** auto/empty → en; иначе берём первые 2 буквы (ru, uk, en, de...). */
    private fun normalizeLang(lang: String): String {
        if (lang.isBlank() || lang == "auto") return "en"
        return lang.substringBefore('-').lowercase()
    }

    fun release() {
        localWhisper?.release()
        localWhisper = null
        apiWhisper = null
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
        _state.value = WhisperState.Idle
    }
}
