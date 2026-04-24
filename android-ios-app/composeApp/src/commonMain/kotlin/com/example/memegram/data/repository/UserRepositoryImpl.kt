package com.example.memegram.data.repository

import com.example.memegram.auth.SessionRefresher
import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.wipe.ClientDataWiper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserRepositoryImpl(
    private val api: ApiService,
    private val clientDataWiper: ClientDataWiper,
    private val sessionRefresher: SessionRefresher,
) : UserRepository {

    private val _profile = MutableStateFlow<UserProfileResponse?>(null)
    override val profile: StateFlow<UserProfileResponse?> = _profile.asStateFlow()

    override suspend fun loadProfile(): Result<UserProfileResponse> = runCatching {
        api.getMe().also { _profile.value = it }
    }

    override suspend fun updateProfile(request: UpdateProfileRequest): Result<UserProfileResponse> = runCatching {
        api.updateMe(request).also { _profile.value = it }
    }

    override suspend fun loadSettings(): Result<UserSettingsResponse> = runCatching {
        api.getMySettings()
    }

    override suspend fun updateSettings(request: UpdateSettingsRequest): Result<UserSettingsResponse> = runCatching {
        api.updateMySettings(request)
    }

    override suspend fun deleteAccount(): Result<Boolean> = runCatching {
        val response = api.deleteAccount()
        if (!response.success) {
            throw Exception("Server returned success=false")
        }
        runCatching { clientDataWiper.wipeAll() }
            .onFailure { println("MemegramDebug [AccountDelete] wipeAll threw despite catches: ${it.message}") }
        runCatching { sessionRefresher.markNoCredentials() }
            .onFailure { println("MemegramDebug [AccountDelete] markNoCredentials failed: ${it.message}") }
        _profile.value = null
        true
    }
}
