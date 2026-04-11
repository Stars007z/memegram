package com.example.memegram.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress

actual fun httpClientEngine(): HttpClientEngineFactory<*> = OkHttp

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = OkHttpClient.Builder()
            .dns(Dns { hostname ->
                when (hostname) {
                    "minio" -> listOf(InetAddress.getByName("10.0.2.2"))
                    else    -> Dns.SYSTEM.lookup(hostname)
                }
            })
            .build()
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
}