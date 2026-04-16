package com.example.memegram.translation

/**
 * Android actual: creates NLLB-200 translation service.
 *
 * Uses NLLB-200-distilled-600M (single model, 200 languages, ~300MB INT8).
 * Language detection: ScriptDetector (heuristic) + ML Kit Language ID.
 */
actual fun createTranslationService(): TranslationService =
    NllbTranslationService(com.example.memegram.AppContextHolder.context)
