package com.example.memegram.transcription

import io.ktor.client.HttpClient

actual fun createTranscriptionService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): TranscriptionService =
    WhisperTranscriptionService(
        context = com.example.memegram.AppContextHolder.context,
        httpClient = httpClient,
        modelBaseUrl = modelBaseUrl,
    )
