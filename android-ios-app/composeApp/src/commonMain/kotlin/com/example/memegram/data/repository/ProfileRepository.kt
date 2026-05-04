package com.example.memegram.data.repository

import com.example.memegram.data.files.clearAvatarsCache
import com.example.memegram.data.files.deleteAvatarBytes
import com.example.memegram.data.models.UserProfileResponse
import com.example.memegram.data.network.ApiService
import com.example.memegram.database.AppDatabase
import com.example.memegram.database.UserProfileEntity
import com.example.memegram.DeletedPeerStore
import com.russhwolf.settings.Settings
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
    private val settings: Settings,
) {
    private val staleThresholdMs: Long = 15 * 60 * 1000L
    private val failedFetchRetryMs: Long = 60 * 1000L
    private val failedFetches = mutableMapOf<String, Long>()

    private val _updates = MutableSharedFlow<UserProfileResponse>(extraBufferCapacity = 32)
    val updates: SharedFlow<UserProfileResponse> = _updates.asSharedFlow()

    suspend fun getCached(userId: String): UserProfileResponse? = withContext(Dispatchers.Default) {
        runCatching {
            database.appDatabaseQueries.selectUserProfileById(userId).executeAsOneOrNull()?.toResponse()
                ?: if (DeletedPeerStore.isUserDeleted(settings, userId)) {
                    UserProfileResponse(id = userId, isDeleted = true)
                } else null
        }.getOrNull()
    }

    suspend fun getOrFetch(userId: String, forceRefresh: Boolean = false): UserProfileResponse? {
        val cached = getCached(userId)
        val now = Clock.System.now().toEpochMilliseconds()
        val lastFailure = failedFetches[userId] ?: 0L
        val recentlyFailed = !forceRefresh && lastFailure > 0 && now - lastFailure < failedFetchRetryMs
        val needsFetch = !recentlyFailed && (forceRefresh || cached == null || isStale(userId))
        if (needsFetch) {
            runCatching { fetchAndCache(userId) }
                .onSuccess { failedFetches.remove(userId) }
                .onFailure { failedFetches[userId] = now }
                .getOrNull()
                ?.let { return it }
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
        val effective = profile.withDeletedMarker()
        if (effective.isDeleted) DeletedPeerStore.markUserDeleted(settings, effective.id)
        withContext(Dispatchers.Default) {
            runCatching {
                database.appDatabaseQueries.upsertUserProfile(
                    userId                   = effective.id,
                    username                 = effective.username,
                    userPublicKey            = effective.userPublicKey,
                    bio                      = effective.bio,
                    isDeleted                = if (effective.isDeleted) 1L else 0L,
                    avatarMediaId            = effective.avatarMediaId,
                    profileBackgroundMediaId = effective.profileBackgroundMediaId,
                    lastActive               = effective.lastActive,
                    isPeerBlocked            = if (effective.isPeerBlocked) 1L else 0L,
                    isBlockedByPeer          = if (effective.isBlockedByPeer) 1L else 0L,
                    cachedAt                 = Clock.System.now().toEpochMilliseconds()
                )
            }
        }
        _updates.tryEmit(effective)
        failedFetches.remove(effective.id)
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
        val fresh = api.getUserById(userId).withDeletedMarker()
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
    ).withDeletedMarker()

    private fun UserProfileResponse.withDeletedMarker(): UserProfileResponse {
        val deleted = isDeleted || DeletedPeerStore.isUserDeleted(settings, id)
        return if (deleted) copy(isDeleted = true, avatarMediaId = null, profileBackgroundMediaId = null)
        else this
    }
}
