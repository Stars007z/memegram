package com.example.memegram

import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("api/v1/auth/login/init")
    suspend fun loginInit(@Body body: LoginInitRequest): LoginInitResponse

    @POST("api/v1/auth/login/complete")
    suspend fun loginComplete(@Body body: LoginCompleteRequest): AuthResponse

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest): LogoutResponse
}
