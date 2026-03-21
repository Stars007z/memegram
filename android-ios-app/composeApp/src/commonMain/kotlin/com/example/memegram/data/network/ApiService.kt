package com.example.memegram.data.network

import com.example.memegram.data.models.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.*
import io.ktor.http.isSuccess

class ApiService(private val client: HttpClient) {
    private val baseUrl = "http://10.0.2.2:8000"

    suspend fun register(body: RegisterRequest): AuthResponse {
        val response = client.post("$baseUrl/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw Exception("Регистрация: ${response.status.value} — ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun loginInit(body: LoginInitRequest): LoginInitResponse {
        val response = client.post("$baseUrl/api/v1/auth/login-init") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw Exception("LoginInit: ${response.status.value} — ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun loginComplete(body: LoginCompleteRequest): AuthResponse {
        val response = client.post("$baseUrl/api/v1/auth/login-complete") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw Exception("LoginComplete: ${response.status.value} — ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun logout(body: LogoutRequest): LogoutResponse {
        val response = client.post("$baseUrl/api/v1/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw Exception("Logout: ${response.status.value} — ${response.bodyAsText()}")
        }
        return response.body()
    }
}