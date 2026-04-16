package com.example.memegram.translation

data class TranslationResult(
    val translatedText: String,
    val detectedSourceLang: String
)

interface TranslationService {
    suspend fun translate(
        text: String,
        sourceLang: String? = null,
        targetLang: String
    ): TranslationResult

    suspend fun identifyLanguage(text: String): String?

    suspend fun ensureModelReady(langCode: String)

    fun close()
}

expect fun createTranslationService(): TranslationService
