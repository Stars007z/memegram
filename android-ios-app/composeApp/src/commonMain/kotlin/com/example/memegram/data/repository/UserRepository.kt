package com.example.memegram.data.repository

import com.example.memegram.data.models.*
import kotlinx.coroutines.flow.StateFlow

interface UserRepository {
    val profile: StateFlow<UserProfileResponse?>
    suspend fun loadProfile(): Result<UserProfileResponse>
    suspend fun updateProfile(request: UpdateProfileRequest): Result<UserProfileResponse>
    suspend fun loadSettings(): Result<UserSettingsResponse>
    suspend fun updateSettings(request: UpdateSettingsRequest): Result<UserSettingsResponse>
    suspend fun deleteAccount(): Result<Boolean>
}