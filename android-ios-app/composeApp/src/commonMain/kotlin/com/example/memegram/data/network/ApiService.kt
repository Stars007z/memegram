package com.example.memegram.data.network

import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class ApiService(
    private val client: HttpClient,
    private val sessionManager: SessionManager
) {
    private val baseUrl = "http://10.0.2.2:8000"

    suspend fun register(body: RegisterRequest): AuthResponse {
        val response = client.post("$baseUrl/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("Регистрация: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun loginInit(body: LoginInitRequest): LoginInitResponse {
        val response = client.post("$baseUrl/api/v1/auth/login-init") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("LoginInit: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun loginComplete(body: LoginCompleteRequest): AuthResponse {
        val response = client.post("$baseUrl/api/v1/auth/login-complete") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("LoginComplete: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun logout(body: LogoutRequest): LogoutResponse {
        val response = client.post("$baseUrl/api/v1/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("Logout: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    // User-service endpoints

    private fun token() = sessionManager.getAccessToken() ?: ""

    suspend fun getMe(): UserProfileResponse =
        client.get("$baseUrl/api/v1/user/me") {
            bearerAuth(token())
        }.body()

    suspend fun updateMe(request: UpdateProfileRequest): UserProfileResponse =
        client.patch("$baseUrl/api/v1/user/me") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun getMySettings(): UserSettingsResponse =
        client.get("$baseUrl/api/v1/user/me/settings") {
            bearerAuth(token())
        }.body()

    suspend fun updateMySettings(request: UpdateSettingsRequest): UserSettingsResponse =
        client.patch("$baseUrl/api/v1/user/me/settings") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun deleteMe() {
        val response = client.delete("$baseUrl/api/v1/user/me") { bearerAuth(token()) }
        if (response.status.value >= 500) {
            throw Exception("deleteMe: ${response.status.value} ${response.bodyAsText()}")
        }
    }


    suspend fun getUserById(userId: String): UserProfileResponse =
        client.get("$baseUrl/api/v1/user/$userId") {
            bearerAuth(token())
        }.body()

    suspend fun getUserByPublicKey(key: String): UserProfileResponse =
        client.get("$baseUrl/api/v1/user/by-key/$key") {
            bearerAuth(token())
        }.body()
}