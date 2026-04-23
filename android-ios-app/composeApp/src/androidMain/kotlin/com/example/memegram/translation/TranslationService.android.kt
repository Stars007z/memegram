package com.example.memegram.translation

import io.ktor.client.HttpClient

/**
 * Android actual: creates NLLB-200 translation service.
 *
 * Uses NLLB-200-distilled-600M (single model, 200 languages, ~300MB INT8).
 * The model is downloaded on demand from Cloudflare R2 (modelBaseUrl).
 */
actual fun createTranslationService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): TranslationService =
    NllbTranslationService(
        context = com.example.memegram.AppContextHolder.context,
        httpClient = httpClient,
        modelBaseUrl = modelBaseUrl,
    )
