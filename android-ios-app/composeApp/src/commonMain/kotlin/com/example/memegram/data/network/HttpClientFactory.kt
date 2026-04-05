package com.example.memegram.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory

expect fun httpClientEngine(): HttpClientEngineFactory<*>

expect fun createHttpClient(): HttpClient