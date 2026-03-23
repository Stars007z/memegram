package com.example.memegram.data.repository

import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserRepositoryImpl(private val api: ApiService) : UserRepository {

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
        api.deleteMe()
        true
    }
}