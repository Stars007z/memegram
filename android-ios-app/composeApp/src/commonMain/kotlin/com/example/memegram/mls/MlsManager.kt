package com.example.memegram.mls

import com.example.memegram.data.local.SessionManager
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class MlsManager(
    private val sessionManager: SessionManager,
    private val settings: Settings
) {
    private val identity: String
        get() = sessionManager.getUserId() ?: "unknown"

    companion object {
        private const val KEY_PROVIDER_STATE = "mls_provider_state"
        private const val KEY_SIGNING_KEY = "mls_signing_key"
        private const val KEY_KP_COUNT = "mls_kp_count"
        private const val KEY_GROUP_PREFIX = "mls_group_"
        const val MIN_KEY_PACKAGES = 10
        const val BATCH_KEY_PACKAGES = 50
    }

    private val mutex = Mutex()
    private var _client: MlsPlatformClient? = null
    private var _stateDirty = false
    private val myRecentCommits = mutableSetOf<String>()

// ── Клиент ────────────────────────────────────────────────────────

    private fun getOrCreateClient(): MlsPlatformClient =
        _client ?: loadClient().also { _client = it }

    private fun loadClient(): MlsPlatformClient {
        val stateB64 = settings.getStringOrNull(KEY_PROVIDER_STATE)
        val keyB64   = settings.getStringOrNull(KEY_SIGNING_KEY)
        return if (stateB64 != null && keyB64 != null) {
            println("MemegramDebug [MLS] ✅ loadClient: ВОССТАНОВЛЕНИЕ из state. " +
                    "identity=$identity, state=${stateB64.length} chars, " +
                    "key=${keyB64.length} chars")
            restoreMlsClient(
                identity      = identity,
                providerState = Base64.decode(stateB64),
                signingKey    = Base64.decode(keyB64)
            )
        } else {
            println("MemegramDebug [MLS] ⚠️ loadClient: НОВЫЙ клиент — state отсутствует! " +
                    "identity=$identity, " +
                    "statePresent=${stateB64 != null}, keyPresent=${keyB64 != null}. " +
                    "Все загруженные на сервер key packages УТЕРЯНЫ.")
            createMlsClient(identity)
        }
    }

    private fun saveState() {
        val c = _client ?: run {
            println("MemegramDebug [MLS] ⚠️ saveState: _client == null, пропускаем")
            return
        }
        val stateBefore = settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0
        settings[KEY_PROVIDER_STATE] = Base64.encode(c.exportProviderState())
        settings[KEY_SIGNING_KEY]    = Base64.encode(c.exportSigningKey())
        _stateDirty = false
        val stateAfter = settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0
        println("MemegramDebug [MLS] 💾 saveState: $stateBefore → $stateAfter chars " +
                "(delta: ${stateAfter - stateBefore}), identity=$identity")
    }

    private fun markDirty() { _stateDirty = true }

    suspend fun flushState() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_stateDirty) {
                println("MemegramDebug [MLS] flushState: dirty=true, сохраняем")
                saveState()
            } else {
                println("MemegramDebug [MLS] flushState: dirty=false, пропускаем")
            }
        }
    }

// ── Инициализация ─────────────────────────────────────────────────

    suspend fun initialize() = withContext(Dispatchers.Default) {
        mutex.withLock {
            val hadState   = settings.getStringOrNull(KEY_PROVIDER_STATE) != null
            val kpCount    = settings.getInt(KEY_KP_COUNT, 0)
            println("MemegramDebug [MLS] ───── initialize() ─────")
            println("MemegramDebug [MLS]   identity   = $identity")
            println("MemegramDebug [MLS]   hadState   = $hadState")
            println("MemegramDebug [MLS]   kpCount    = $kpCount (MIN=$MIN_KEY_PACKAGES)")
            println("MemegramDebug [MLS]   _client    = ${if (_client != null) "уже создан (reuse)" else "null (создаём)"}")
            getOrCreateClient()
            saveState()
            if (!hadState) {
                println("MemegramDebug [MLS] ⚠️ initialize(): создан НОВЫЙ клиент. " +
                        "Необходимо сгенерировать и загрузить key packages!")
            } else {
                println("MemegramDebug [MLS] ✅ initialize(): клиент восстановлен из state")
            }
            println("MemegramDebug [MLS] ────────────────────────")
        }
    }

// ── Key Packages ──────────────────────────────────────────────────

    suspend fun generateKeyPackages(count: Int = BATCH_KEY_PACKAGES): List<String> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val stateBefore = settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0
                val kpBefore    = settings.getInt(KEY_KP_COUNT, 0)
                println("MemegramDebug [MLS] generateKeyPackages($count): " +
                        "state=$stateBefore chars, kpCount=$kpBefore")
                val packages = (0 until count).map {
                    Base64.encode(getOrCreateClient().generateKeyPackage())
                }
                saveState()
                val stateAfter = settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0
                settings[KEY_KP_COUNT] = kpBefore + count
                println("MemegramDebug [MLS] ✅ generateKeyPackages: " +
                        "сгенерировано=$count, state $stateBefore→$stateAfter chars, " +
                        "kpCount ${kpBefore}→${kpBefore + count}")
                println("MemegramDebug [MLS]   Первый пакет (20 chars): ${packages.firstOrNull()?.take(20)}...")
                packages
            }
        }

    fun needsKeyPackages(): Boolean =
        settings.getInt(KEY_KP_COUNT, 0) < MIN_KEY_PACKAGES

    fun onKeyPackageConsumed() {
        val c = settings.getInt(KEY_KP_COUNT, 0)
        if (c > 0) {
            settings[KEY_KP_COUNT] = c - 1
            println("MemegramDebug [MLS] onKeyPackageConsumed: kpCount $c → ${c - 1}")
        }
    }

// ── Маппинг ID ────────────────────────────────────────────────────

    private fun getMlsGroupId(conversationId: String): ByteArray {
        val b64 = settings.getStringOrNull("mls_mapping_$conversationId")
            ?: run {
                println("MemegramDebug [MLS] ❌ getMlsGroupId: маппинг НЕ НАЙДЕН для $conversationId!")
                throw IllegalStateException(
                    "MLS mapping missing for conversation $conversationId. Group not bound!"
                )
            }
        return Base64.decode(b64)
    }

    fun hasGroup(conversationId: String): Boolean {
        val hasGroupKey   = settings.getStringOrNull("$KEY_GROUP_PREFIX$conversationId") != null
        val hasMappingKey = settings.getStringOrNull("mls_mapping_$conversationId") != null
        val result = hasGroupKey && hasMappingKey
        println("MemegramDebug [MLS] hasGroup($conversationId): " +
                "groupKey=$hasGroupKey, mappingKey=$hasMappingKey → $result")
        return result
    }

    fun bindConversation(conversationId: String, mlsGroupId: String) {
        println("MemegramDebug [MLS] bindConversation: $conversationId → groupId=${mlsGroupId.take(20)}...")
        settings["mls_mapping_$conversationId"] = Base64.encode(mlsGroupId.encodeToByteArray())
        markGroupKnown(conversationId)
        settings.remove("$KEY_GROUP_PREFIX$mlsGroupId")
    }

// ── Создание группы (инициатор) ───────────────────────────────────
    private fun normalizeB64(b64: String): String = b64.replace("\\s+".toRegex(), "")

    suspend fun createEmptyGroup(mlsGroupId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            println("MemegramDebug [MLS] ───── createEmptyGroup() ─────")
            val groupIdBytes = mlsGroupId.encodeToByteArray()
            val c = getOrCreateClient()
            c.createGroupWithId(groupIdBytes)
            saveState()
            println("MemegramDebug [MLS] ✅ Пустая группа создана")
            println("MemegramDebug [MLS] ────────────────────────")
        }
    }
    suspend fun createGroup(
        mlsGroupId: String,
        peerKeyPackageB64: String
    ): CreateGroupResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            println("MemegramDebug [MLS] ───── createGroup() ─────")
            println("MemegramDebug [MLS]   identity=$identity")
            println("MemegramDebug [MLS]   mlsGroupId=${mlsGroupId.take(20)}...")
            println("MemegramDebug [MLS]   peerKeyPackage=${peerKeyPackageB64.take(20)}...")
            println("MemegramDebug [MLS]   state size=${settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0} chars")
            val groupIdBytes = mlsGroupId.encodeToByteArray()
            val c = getOrCreateClient()
            c.createGroupWithId(groupIdBytes)
            val bundle = c.addMember(groupIdBytes, Base64.decode(peerKeyPackageB64))
            c.mergePendingCommit(groupIdBytes)
            val commitB64 = Base64.encode(bundle.commit)
            myRecentCommits.add(normalizeB64(commitB64))
            saveState()
            println("MemegramDebug [MLS]   ✅ addMember — OK")
            println("MemegramDebug [MLS]   welcome size: ${bundle.welcome.size} bytes")
            println("MemegramDebug [MLS]   commit  size: ${bundle.commit.size} bytes")
            println("MemegramDebug [MLS] ────────────────────────")
            CreateGroupResult(
                welcomeB64 = Base64.encode(bundle.welcome),
                commitB64  = commitB64
            )
        }
    }

// ── Вступление в группу (получатель Welcome) ──────────────────────

    fun getMyUserId(): String = sessionManager.getUserId() ?: ""
    fun getMyDeviceId(): String = sessionManager.getDeviceId() ?: ""

    suspend fun createGroupForMultiple(
        mlsGroupId: String,
        peerPackages: List<com.example.memegram.data.models.UserDeviceKeyPackage>
    ): List<com.example.memegram.data.models.DeviceWelcome> = withContext(Dispatchers.Default) {
        mutex.withLock {
            println("MemegramDebug [MLS] ───── createGroupForMultiple() ─────")
            val groupIdBytes = mlsGroupId.encodeToByteArray()
            val c = getOrCreateClient()
            c.createGroupWithId(groupIdBytes)

            val welcomes = mutableListOf<com.example.memegram.data.models.DeviceWelcome>()

            for (kp in peerPackages) {
                try {
                    val bundle = c.addMember(groupIdBytes, kotlin.io.encoding.Base64.decode(kp.keyPackageData))
                    welcomes.add(
                        com.example.memegram.data.models.DeviceWelcome(
                            deviceId = kp.deviceId,
                            welcomeData = kotlin.io.encoding.Base64.encode(bundle.welcome)
                        )
                    )
                    println("MemegramDebug [MLS]   ✅ Устройство ${kp.deviceId} добавлено в группу")
                } catch (e: Exception) {
                    println("MemegramDebug [MLS]   ❌ Ошибка добавления устройства ${kp.deviceId}: ${e.message}")
                }
            }

            saveState()
            println("MemegramDebug [MLS] ────────────────────────")
            welcomes
        }
    }

    suspend fun processWelcome(conversationId: String, welcomeB64: String) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val stateSize = settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0
                val kpCount   = settings.getInt(KEY_KP_COUNT, 0)

                try {
                    val c = getOrCreateClient()
                    val returnedGroupIdBytes = c.joinFromWelcome(Base64.decode(welcomeB64))
                    val groupIdB64 = Base64.encode(returnedGroupIdBytes)
                    settings["mls_mapping_$conversationId"] = groupIdB64
                    saveState()

                    val realEpoch = try {
                        c.getGroupEpoch(returnedGroupIdBytes).toLong()
                    } catch (_: Exception) { 0L }

                    markGroupKnown(conversationId, epoch = realEpoch)
                    println("MemegramDebug [MLS] ✅ processWelcome OK: " +
                            "groupId=${groupIdB64.take(20)}..., realMlsEpoch=$realEpoch")
                    println("MemegramDebug [MLS] ────────────────────────")
                } catch (e: Exception) {
                    println("MemegramDebug [MLS] ❌ processWelcome FAILED: ${e.message}")
                    throw e
                }
            }
        }

// ── Обработка Commit ──────────────────────────────────────────────

    suspend fun processCommit(conversationId: String, commitB64: String): Boolean =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val normalized = normalizeB64(commitB64)
                if (myRecentCommits.contains(normalized)) {
                    println("MemegramDebug [MLS] ✅ processCommit: это наш коммит, безопасно пропускаем.")
                    return@withLock true
                }

                try {
                    val groupId = getMlsGroupId(conversationId)
                    val c = getOrCreateClient()

                    val epochBefore = try { c.getGroupEpoch(groupId).toLong() } catch (_: Exception) { -1L }

                    val result = c.processMessage(groupId, Base64.decode(commitB64))

                    val epochAfter = try { c.getGroupEpoch(groupId).toLong() } catch (_: Exception) { -1L }
                    val members = try { c.memberCount(groupId).toLong() } catch (_: Exception) { -1L }

                    when (result) {
                        is IncomingMessageKt.CommitApplied -> {
                            saveState()
                            println("MemegramDebug [MLS] ✅ processCommit: CommitApplied " +
                                    "epoch $epochBefore→$epochAfter, members=$members")
                            return@withLock true
                        }
                        is IncomingMessageKt.Proposal -> {
                            saveState()
                            println("MemegramDebug [MLS] ⚠️ processCommit: получен Proposal (не commit), " +
                                    "epoch=$epochAfter, members=$members — НЕ считаем как успех")
                            return@withLock false
                        }
                        else -> {
                            saveState()
                            println("MemegramDebug [MLS] ⚠️ processCommit: result=${result::class.simpleName}, " +
                                    "epoch=$epochAfter, members=$members")
                            return@withLock false
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("epoch differs") || msg.contains("too old") || msg.contains("forward secrecy")) {
                        println("MemegramDebug [MLS] ⚠️ Пропускаем устаревший коммит: $msg")
                        return@withLock false
                    } else {
                        println("MemegramDebug [MLS] ❌ Критическая ошибка MLS: $msg")
                        throw e
                    }
                }
            }
        }

// ── Шифрование ────────────────────────────────────────────────────

    suspend fun encrypt(conversationId: String, plaintext: String): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                println("MemegramDebug [MLS] encrypt: conversationId=$conversationId, " +
                        "textLen=${plaintext.length}")
                val groupId = getMlsGroupId(conversationId)
                val ciphertext = getOrCreateClient()
                    .encryptMessage(groupId, plaintext.encodeToByteArray())
                markDirty()
                val result = Base64.encode(ciphertext)
                println("MemegramDebug [MLS] ✅ encrypt: ciphertext=${result.length} chars")
                result
            }
        }

// ── Дешифрование ──────────────────────────────────────────────────

    suspend fun decrypt(conversationId: String, ciphertextB64: String): String? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                println("MemegramDebug [MLS] decrypt: conversationId=$conversationId, cipherLen=${ciphertextB64.length}")
                try {
                    val groupId = getMlsGroupId(conversationId)
                    val c = getOrCreateClient()

                    val realEpoch = try { c.getGroupEpoch(groupId).toLong() } catch (_: Exception) { -1L }
                    val metaEpoch = getGroupEpoch(conversationId)
                    println("MemegramDebug [MLS] decrypt: realMlsEpoch=$realEpoch, metadataEpoch=$metaEpoch")

                    val result = c.processMessage(groupId, kotlin.io.encoding.Base64.decode(ciphertextB64))
                    markDirty()
                    if (result is IncomingMessageKt.Application) {
                        val text = result.data.decodeToString()
                        println("MemegramDebug [MLS] ✅ decrypt: OK, textLen=${text.length}")
                        return@withLock text
                    } else {
                        println("MemegramDebug [MLS] ⚠️ decrypt: не Application ($result)")
                        return@withLock null
                    }
                } catch (e: Exception) {
                    println("MemegramDebug [MLS] ❌ decrypt FAILED: ${e.message}")
                    return@withLock null
                }
            }
        }

    suspend fun addMemberToGroup(
        conversationId: String,
        keyPackageB64: String
    ): AddMemberResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val groupId = getMlsGroupId(conversationId)
            val c = getOrCreateClient()

            try { c.clearPendingProposals(groupId) } catch (_: Exception) {}
            try { c.clearPendingCommit(groupId) } catch (_: Exception) {}

            val memberCount = try { c.memberCount(groupId) } catch (_: Exception) { -1L }
            val realEpoch = try { c.getGroupEpoch(groupId).toLong() } catch (_: Exception) { -1L }
            println("MemegramDebug [MLS] addMemberToGroup: conversationId=$conversationId, " +
                    "members=$memberCount, realEpoch=$realEpoch")

            try {
                val bundle = c.addMember(groupId, Base64.decode(keyPackageB64))
                val commitB64 = Base64.encode(bundle.commit)
                myRecentCommits.add(normalizeB64(commitB64))
                saveState()
                println("MemegramDebug [MLS] ✅ addMemberToGroup: welcome=${bundle.welcome.size}b, commit=${bundle.commit.size}b")
                AddMemberResult(Base64.encode(bundle.welcome), commitB64)
            } catch (e: Exception) {
                println("MemegramDebug [MLS] ❌ addMemberToGroup FAILED: ${e.message}")
                println("MemegramDebug [MLS]   memberCount=$memberCount, realEpoch=$realEpoch")
                throw e
            }
        }
    }


    suspend fun exportCredentials(): MlsCredentials = withContext(Dispatchers.Default) {
        mutex.withLock {
            val c = getOrCreateClient()

            val signingKeyBytes = c.exportSigningKey()

            val kpBytes = c.generateKeyPackage()
            settings[KEY_KP_COUNT] = settings.getInt(KEY_KP_COUNT, 0) + 1
            saveState()

            val credBytes = identity.encodeToByteArray()

            MlsCredentials(
                identityKeyPub = Base64.encode(signingKeyBytes),
                initKeyPub     = Base64.encode(kpBytes),
                credentialData = Base64.encode(credBytes)
            )
        }
    }

// ── Статус группы ─────────────────────────────────────────────────

    fun getGroupEpoch(conversationId: String): Long =
        settings.getStringOrNull("$KEY_GROUP_PREFIX$conversationId")?.toLongOrNull() ?: -1L

    private fun markGroupKnown(conversationId: String, epoch: Long = 0L) {
        settings["$KEY_GROUP_PREFIX$conversationId"] = epoch.toString()
        println("MemegramDebug [MLS] markGroupKnown: $conversationId, epoch=$epoch")
    }

    fun updateGroupEpoch(conversationId: String, newEpoch: Long) {
        if (hasGroup(conversationId)) {
            settings["$KEY_GROUP_PREFIX$conversationId"] = newEpoch.toString()
            println("MemegramDebug [MLS] updateGroupEpoch: $conversationId → $newEpoch")
        }
    }

    suspend fun getRealMlsEpoch(conversationId: String): Long = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val groupId = getMlsGroupId(conversationId)
                getOrCreateClient().getGroupEpoch(groupId).toLong()
            } catch (_: Exception) { -1L }
        }
    }

    fun clearAll() {
        println("MemegramDebug [MLS] ⚠️ clearAll() вызван! identity=$identity, " +
                "state size=${settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0} chars")
        settings.remove(KEY_PROVIDER_STATE)
        settings.remove(KEY_SIGNING_KEY)
        settings.remove(KEY_KP_COUNT)
        _client     = null
        _stateDirty = false
        println("MemegramDebug [MLS] clearAll() завершён — всё удалено")
    }

    fun migrateGroupId(oldConversationId: String, newConversationId: String) {
        val oldKey = "$KEY_GROUP_PREFIX$oldConversationId"
        val newKey = "$KEY_GROUP_PREFIX$newConversationId"
        val state  = settings.getStringOrNull(oldKey) ?: run {
            println("MemegramDebug [MLS] migrateGroupId: $oldConversationId не найден, пропускаем")
            return
        }
        settings[newKey] = state
        settings.remove(oldKey)
        val mappingB64 = settings.getStringOrNull("mls_mapping_$oldConversationId")
        if (mappingB64 != null) {
            settings["mls_mapping_$newConversationId"] = mappingB64
            settings.remove("mls_mapping_$oldConversationId")
        }
        println("MemegramDebug [MLS] migrateGroupId: $oldConversationId → $newConversationId OK")
    }

    suspend fun leaveGroup(conversationId: String): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val mlsGroupIdBytes = getMlsGroupId(conversationId)
            val commitBytes = getOrCreateClient().leaveGroup(mlsGroupIdBytes)

            val commitB64 = Base64.encode(commitBytes)
            myRecentCommits.add(normalizeB64(commitB64))

            settings.remove("mls_mapping_$conversationId")
            settings.remove("$KEY_GROUP_PREFIX$conversationId")
            saveState()
            commitB64
        }
    }

    /**
     * Deletes a group from local MLS state without generating any MLS proposals.
     * Use this when the member has already left via the server API and only needs
     * to clean up local state. This is safer than [leaveGroup] because it won't fail
     * if the group state is corrupted, and doesn't create an unnecessary proposal.
     */
    suspend fun deleteLocalGroup(conversationId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val mlsGroupIdBytes = getMlsGroupId(conversationId)
                getOrCreateClient().deleteGroup(mlsGroupIdBytes)
            } catch (e: Exception) {
                println("MemegramDebug [MLS] deleteLocalGroup: Rust deleteGroup failed (OK): ${e.message}")
            }

            settings.remove("mls_mapping_$conversationId")
            settings.remove("$KEY_GROUP_PREFIX$conversationId")
            saveState()
            println("MemegramDebug [MLS] deleteLocalGroup: $conversationId removed")
        }
    }

    /**
     * Creates a Remove Commit for a member identified by [targetUserId].
     * Per RFC 9420 §12.2, only a *remaining* member can commit the removal.
     * Returns the base64-encoded commit bytes to send to the server.
     */
    suspend fun removeMember(conversationId: String, targetUserId: String): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val groupId = getMlsGroupId(conversationId)
                val c = getOrCreateClient()

                val epochBefore = try { c.getGroupEpoch(groupId).toLong() } catch (_: Exception) { -1L }
                println("MemegramDebug [MLS] removeMember: conv=$conversationId, " +
                        "target=$targetUserId, epochBefore=$epochBefore")

                val commitBytes = c.removeMemberByIdentity(groupId, targetUserId)
                val commitB64 = Base64.encode(commitBytes)
                myRecentCommits.add(normalizeB64(commitB64))
                saveState()

                println("MemegramDebug [MLS] removeMember: commit created, ${commitBytes.size} bytes")
                commitB64
            }
        }

    suspend fun mergePendingCommit(conversationId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val groupId = getMlsGroupId(conversationId)
            getOrCreateClient().mergePendingCommit(groupId)
            saveState()
            println("MemegramDebug [MLS] ✅ Pending commit успешно смержен")
        }
    }

    suspend fun clearPendingCommit(conversationId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val groupId = getMlsGroupId(conversationId)
            getOrCreateClient().clearPendingCommit(groupId)
            saveState()
            println("MemegramDebug [MLS] ⚠️ Pending commit отменен (ошибка сети)")
        }
    }
}


data class CreateGroupResult(
    val welcomeB64: String,
    val commitB64: String
)

data class MlsCredentials(
    val identityKeyPub: String,
    val initKeyPub: String,
    val credentialData: String
)

data class AddMemberResult(
    val welcomeB64: String,
    val commitB64: String
)