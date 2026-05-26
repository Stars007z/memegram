package com.example.memegram.nsfw

import io.ktor.client.HttpClient

actual fun createNsfwService(
    httpClient: HttpClient,
    modelBaseUrl: String,
): NsfwService = AndroidNsfwService(
    context = com.example.memegram.AppContextHolder.context,
    httpClient = httpClient,
    modelBaseUrl = modelBaseUrl,
)
