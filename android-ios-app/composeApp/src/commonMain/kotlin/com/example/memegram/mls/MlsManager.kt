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
            println("MemegramDebug [MLS]   ✅ createGroupWithId — OK")
            val bundle = c.addMember(groupIdBytes, Base64.decode(peerKeyPackageB64))
            saveState()
            println("MemegramDebug [MLS]   ✅ addMember — OK")
            println("MemegramDebug [MLS]   welcome size: ${bundle.welcome.size} bytes")
            println("MemegramDebug [MLS]   commit  size: ${bundle.commit.size} bytes")
            println("MemegramDebug [MLS] ────────────────────────")
            CreateGroupResult(
                welcomeB64 = Base64.encode(bundle.welcome),
                commitB64  = Base64.encode(bundle.commit)
            )
        }
    }

// ── Вступление в группу (получатель Welcome) ──────────────────────

    suspend fun processWelcome(conversationId: String, welcomeB64: String) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val stateSize = settings.getStringOrNull(KEY_PROVIDER_STATE)?.length ?: 0
                val kpCount   = settings.getInt(KEY_KP_COUNT, 0)
                println("MemegramDebug [MLS] ───── processWelcome() ─────")
                println("MemegramDebug [MLS]   conversationId = $conversationId")
                println("MemegramDebug [MLS]   identity       = $identity")
                println("MemegramDebug [MLS]   state size     = $stateSize chars")
                println("MemegramDebug [MLS]   kpCount        = $kpCount")
                println("MemegramDebug [MLS]   welcome size   = ${welcomeB64.length} chars")
                if (stateSize == 0) {
                    println("MemegramDebug [MLS] ❌ КРИТИЧНО: state пуст! " +
                            "Клиент новый — key packages на сервере устарели!")
                }
                if (kpCount == 0) {
                    println("MemegramDebug [MLS] ⚠️ kpCount=0: key packages могли быть " +
                            "израсходованы или никогда не генерировались для этого identity")
                }
                try {
                    val returnedGroupIdBytes =
                        getOrCreateClient().joinFromWelcome(Base64.decode(welcomeB64))
                    val groupIdB64 = Base64.encode(returnedGroupIdBytes)
                    settings["mls_mapping_$conversationId"] = groupIdB64
                    saveState()
                    markGroupKnown(conversationId, epoch = 1L)
                    println("MemegramDebug [MLS] ✅ processWelcome OK: " +
                            "groupId=${groupIdB64.take(20)}...")
                    println("MemegramDebug [MLS] ────────────────────────")
                } catch (e: Exception) {
                    println("MemegramDebug [MLS] ❌ processWelcome FAILED: ${e.message}")
                    println("MemegramDebug [MLS]   Вероятная причина: key package, " +
                            "использованный отправителем (${welcomeB64.take(20)}...), " +
                            "не найден в локальном store. " +
                            "state size=$stateSize, kpCount=$kpCount")
                    println("MemegramDebug [MLS] ────────────────────────")
                    throw e
                }
            }
        }

// ── Обработка Commit ──────────────────────────────────────────────

    suspend fun processCommit(conversationId: String, commitB64: String) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                println("MemegramDebug [MLS] processCommit: conversationId=$conversationId")
                val groupId = getMlsGroupId(conversationId)
                println("MemegramDebug [MLS]   groupId=${Base64.encode(groupId).take(20)}..., " +
                        "commit size=${commitB64.length} chars")
                val result = getOrCreateClient().processMessage(groupId, Base64.decode(commitB64))
                when (result) {
                    is IncomingMessageKt.CommitApplied -> {
                        saveState()
                        println("MemegramDebug [MLS] ✅ processCommit: CommitApplied")
                    }
                    is IncomingMessageKt.Proposal -> {
                        saveState()
                        println("MemegramDebug [MLS] ✅ processCommit: Proposal")
                    }
                    is IncomingMessageKt.Other -> {
                        saveState()
                        println("MemegramDebug [MLS] ⚠️ processCommit: Other")
                    }
                    is IncomingMessageKt.Application -> {
                        println("MemegramDebug [MLS] ⚠️ processCommit: получили Application (не ожидалось!)")
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
                println("MemegramDebug [MLS] decrypt: conversationId=$conversationId, " +
                        "cipherLen=${ciphertextB64.length}")
                val groupId = try {
                    getMlsGroupId(conversationId)
                } catch (e: IllegalStateException) {
                    println("MemegramDebug [MLS] ⚠️ decrypt: группа не найдена (${e.message}), " +
                            "возвращаем null")
                    return@withLock null
                }
                return@withLock try {
                    val result = getOrCreateClient().processMessage(
                        groupId,
                        Base64.decode(ciphertextB64)
                    )
                    markDirty()
                    when (result) {
                        is IncomingMessageKt.Application -> {
                            val text = result.data.decodeToString()
                            println("MemegramDebug [MLS] ✅ decrypt: OK, textLen=${text.length}")
                            text
                        }
                        else -> {
                            println("MemegramDebug [MLS] ⚠️ decrypt: не Application ($result)")
                            null
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("group not found", ignoreCase = true) ||
                        msg.contains("unknown group", ignoreCase = true)
                    ) {
                        println("MemegramDebug [MLS] ⚠️ decrypt: group not found (${e.message})")
                        null
                    } else {
                        println("MemegramDebug [MLS] ❌ decrypt FAILED: ${e.message}")
                        throw MlsDecryptionException("Decrypt failed for $conversationId", e)
                    }
                }
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
}

class MlsDecryptionException(message: String, cause: Throwable) : Exception(message, cause)

data class CreateGroupResult(
    val welcomeB64: String,
    val commitB64: String
)