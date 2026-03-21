package com.example.memegram.data.network

import io.ktor.client.engine.*
import io.ktor.client.engine.darwin.*

actual fun httpClientEngine(): HttpClientEngineFactory<*> = Darwin