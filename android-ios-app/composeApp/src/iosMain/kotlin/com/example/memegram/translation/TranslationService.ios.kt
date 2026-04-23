package com.example.memegram.translation

import io.ktor.client.HttpClient

actual fun createTranslationService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): TranslationService = IosNllbTranslationService(httpClient, modelBaseUrl)
