package com.example.memegram.transcription

import io.ktor.client.HttpClient

actual fun createTranscriptionService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): TranscriptionService = IosWhisperTranscriptionService(httpClient, modelBaseUrl)
