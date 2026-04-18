package com.example.memegram.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun httpClientEngine(): HttpClientEngineFactory<*> = Darwin

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    installCommonNetworking()
}
