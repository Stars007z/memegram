package com.example.memegram.data.repository

import com.example.memegram.data.files.clearAvatarsCache
import com.example.memegram.data.files.deleteAvatarBytes
import com.example.memegram.data.models.UserProfileResponse
import com.example.memegram.data.network.ApiService
import com.example.memegram.database.AppDatabase
import com.example.memegram.database.UserProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ProfileRepository(
    private val api: ApiService,
    private val database: AppDatabase,
) {
    private val staleThresholdMs: Long = 15 * 60 * 1000L

    private val _updates = MutableSharedFlow<UserProfileResponse>(extraBufferCapacity = 32)
    val updates: SharedFlow<UserProfileResponse> = _updates.asSharedFlow()

    suspend fun getCached(userId: String): UserProfileResponse? = withContext(Dispatchers.Default) {
        runCatching {
            database.appDatabaseQueries.selectUserProfileById(userId).executeAsOneOrNull()?.toResponse()
        }.getOrNull()
    }

    suspend fun getOrFetch(userId: String, forceRefresh: Boolean = false): UserProfileResponse? {
        val cached = getCached(userId)
        val needsFetch = forceRefresh || cached == null || isStale(userId)
        if (needsFetch) {
            runCatching { fetchAndCache(userId) }.getOrNull()?.let { return it }
        }
        return cached
    }

    suspend fun refresh(userId: String): UserProfileResponse? = runCatching { fetchAndCache(userId) }.getOrNull()

    suspend fun getByPublicKey(publicKey: String, forceRefresh: Boolean = false): UserProfileResponse? {
        val cached = withContext(Dispatchers.Default) {
            runCatching {
                database.appDatabaseQueries.selectUserProfileByPublicKey(publicKey).executeAsOneOrNull()?.toResponse()
            }.getOrNull()
        }
        if (!forceRefresh && cached != null && !isStale(cached.id)) return cached
        return runCatching {
            val fresh = api.getUserByPublicKey(publicKey)
            upsert(fresh)
            fresh
        }.getOrNull() ?: cached
    }

    suspend fun upsert(profile: UserProfileResponse) {
        withContext(Dispatchers.Default) {
            runCatching {
                database.appDatabaseQueries.upsertUserProfile(
                    userId                   = profile.id,
                    username                 = profile.username,
                    userPublicKey            = profile.userPublicKey,
                    bio                      = profile.bio,
                    isDeleted                = if (profile.isDeleted) 1L else 0L,
                    avatarMediaId            = profile.avatarMediaId,
                    profileBackgroundMediaId = profile.profileBackgroundMediaId,
                    lastActive               = profile.lastActive,
                    isPeerBlocked            = if (profile.isPeerBlocked) 1L else 0L,
                    isBlockedByPeer          = if (profile.isBlockedByPeer) 1L else 0L,
                    cachedAt                 = Clock.System.now().toEpochMilliseconds()
                )
            }
        }
        _updates.tryEmit(profile)
    }

    suspend fun clearOtherProfiles(selfUserId: String) {
        withContext(Dispatchers.Default) {
            runCatching { database.appDatabaseQueries.deleteAllUserProfilesExcept(selfUserId) }
        }
        clearAvatarsCache()
    }

    suspend fun forget(userId: String) {
        val cached = getCached(userId)
        withContext(Dispatchers.Default) {
            runCatching { database.appDatabaseQueries.deleteUserProfile(userId) }
        }
        cached?.avatarMediaId?.let { deleteAvatarBytes(it) }
        cached?.profileBackgroundMediaId?.let { deleteAvatarBytes(it) }
    }

    private suspend fun fetchAndCache(userId: String): UserProfileResponse {
        val fresh = api.getUserById(userId)
        upsert(fresh)
        return fresh
    }

    private suspend fun isStale(userId: String): Boolean {
        val cachedAt = withContext(Dispatchers.Default) {
            runCatching {
                database.appDatabaseQueries.selectUserProfileById(userId).executeAsOneOrNull()?.cachedAt
            }.getOrNull()
        } ?: return true
        return Clock.System.now().toEpochMilliseconds() - cachedAt > staleThresholdMs
    }

    private fun UserProfileEntity.toResponse(): UserProfileResponse = UserProfileResponse(
        id                       = userId,
        username                 = username,
        userPublicKey            = userPublicKey,
        bio                      = bio,
        isDeleted                = isDeleted == 1L,
        avatarMediaId            = avatarMediaId,
        profileBackgroundMediaId = profileBackgroundMediaId,
        lastActive               = lastActive,
        isPeerBlocked            = isPeerBlocked == 1L,
        isBlockedByPeer          = isBlockedByPeer == 1L,
    )
}
