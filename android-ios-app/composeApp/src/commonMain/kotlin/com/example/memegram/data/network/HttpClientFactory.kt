package com.example.memegram.data.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun httpClientEngine(): HttpClientEngineFactory<*>

expect fun createHttpClient(): HttpClient

fun HttpClientConfig<*>.installCommonNetworking() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
    install(HttpTimeout) {
        // Defaults — individual requests can override via timeout { ... } block.
        // Long enough for media uploads and NSFW inference (~30-60s on cold start).
        requestTimeoutMillis = 120_000
        socketTimeoutMillis = 120_000
        connectTimeoutMillis = 15_000
    }
    HttpResponseValidator {
        validateResponse { response ->
            val status = response.status
            if (!status.isSuccess()) {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                throw ApiException.from(status, body)
            }
        }
    }
}
