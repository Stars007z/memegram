package com.example.memegram.data.network

import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*

actual fun httpClientEngine(): HttpClientEngineFactory<*> = OkHttp