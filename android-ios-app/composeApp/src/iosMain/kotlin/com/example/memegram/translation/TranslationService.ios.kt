package com.example.memegram.translation

class IosTranslationService : TranslationService {

    override suspend fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String
    ): TranslationResult {
        return TranslationResult(
            translatedText = text,
            detectedSourceLang = sourceLang ?: "und"
        )
    }

    override suspend fun identifyLanguage(text: String): String? = null

    override suspend fun ensureModelReady(langCode: String) { /* no-op */ }

    override fun close() { /* no-op */ }
}

actual fun createTranslationService(): TranslationService = IosTranslationService()
