package com.example.memegram

import com.example.memegram.data.repository.ContactsRepository
import com.example.memegram.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime


@OptIn(ExperimentalTime::class)
class BlockedUsersCache(
    private val contactsRepository: ContactsRepository,
    private val database: AppDatabase,
) {
    private val _blockedIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedIds: StateFlow<Set<String>> = _blockedIds.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun load() {
        runCatching {
            withContext(Dispatchers.Default) {
                database.appDatabaseQueries.selectAllBlockedUsers().executeAsList().toSet()
            }
        }.getOrNull()?.let { local ->
            if (local.isNotEmpty()) _blockedIds.value = local
        }

        runCatching {
            val ids = mutableSetOf<String>()
            var offset = 0
            val limit = 50
            while (true) {
                val page = contactsRepository.getBlockedUsers(limit, offset).getOrNull() ?: break
                if (page.isEmpty()) break
                ids += page.map { it.blockedUserId }
                if (page.size < limit) break
                offset += limit
            }
            ids
        }.getOrNull()?.let { remote ->
            _blockedIds.value = remote
            persistAll(remote)
        }
    }

    fun add(userId: String) {
        _blockedIds.value = _blockedIds.value + userId
        ioScope.launch {
            runCatching {
                database.appDatabaseQueries.insertBlockedUser(userId, Clock.System.now().toEpochMilliseconds())
            }
        }
    }

    fun remove(userId: String) {
        _blockedIds.value -= userId
        ioScope.launch {
            runCatching { database.appDatabaseQueries.deleteBlockedUser(userId) }
        }
    }

    fun isBlocked(userId: String): Boolean = userId in _blockedIds.value

    suspend fun isBlockedNow(userId: String): Boolean {
        if (isBlocked(userId)) return true
        val blocked = runCatching {
            withContext(Dispatchers.Default) {
                userId in database.appDatabaseQueries.selectAllBlockedUsers().executeAsList()
            }
        }.getOrDefault(false)
        if (blocked) _blockedIds.value = _blockedIds.value + userId
        return blocked
    }

    private fun persistAll(ids: Set<String>) {
        ioScope.launch {
            runCatching {
                database.appDatabaseQueries.transaction {
                    database.appDatabaseQueries.clearBlockedUsers()
                    val now = Clock.System.now().toEpochMilliseconds()
                    ids.forEach { database.appDatabaseQueries.insertBlockedUser(it, now) }
                }
            }
        }
    }
}
