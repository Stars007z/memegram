package com.example.memegram

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.localization.S
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock

// ── UI models ────────────────────────────────────────────────────────

data class StorageCategoryUi(
    val type: String,
    val sizeBytes: Long,
    val messageCount: Long,
    val percentage: Float,
    val color: Color,
    val isSelected: Boolean = true
)

data class ChatStorageUi(
    val conversationId: String,
    val chatName: String,
    val avatarMediaId: String?,
    val totalSize: Long,
    val messageCount: Long,
    val perType: Map<String, Long> = emptyMap()
)

enum class StorageTab { CHATS, MEDIA, FILES, MUSIC }
enum class ChatDetailTab { MEDIA, FILES, MUSIC }

// ── Colors ───────────────────────────────────────────────────────────

val StorageColorPhotos    = Color(0xFFFF7043)
val StorageColorVideos    = Color(0xFF5B8DEF)
val StorageColorDocuments = Color(0xFF66BB6A)
val StorageColorVoice     = Color(0xFFFFCA28)
val StorageColorMusic     = Color(0xFFAB47BC)
val StorageColorText      = Color(0xFF26A69A)
val StorageColorOther     = Color(0xFFEC407A)

fun colorForType(type: String): Color = when (type) {
    "photo" -> StorageColorPhotos
    "video" -> StorageColorVideos
    "file"  -> StorageColorDocuments
    "voice" -> StorageColorVoice
    "music" -> StorageColorMusic
    "text"  -> StorageColorText
    else    -> StorageColorOther
}

fun displayNameForType(type: String): String {
    val s = S.current
    return when (type) {
        "photo" -> s.storagePhotos
        "video" -> s.storageVideos
        "file"  -> s.storageDocuments
        "voice" -> s.storageVoiceMessages
        "music" -> s.storageMusic
        "text"  -> s.storageTextMessages
        else    -> s.storageOther
    }
}

// ── Formatting helpers ───────────────────────────────────────────────

fun formatSizeBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${fmtNum(kotlin.math.round(kb * 10) / 10)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${fmtNum(kotlin.math.round(mb * 10) / 10)} MB"
    val gb = mb / 1024.0
    return "${fmtNum(kotlin.math.round(gb * 100) / 100)} GB"
}

fun formatSizeComponents(bytes: Long): Pair<String, String> {
    if (bytes < 1024) return Pair("$bytes", "B")
    val kb = bytes / 1024.0
    if (kb < 1024) return Pair(fmtNum(kotlin.math.round(kb * 10) / 10), "KB")
    val mb = kb / 1024.0
    if (mb < 1024) return Pair(fmtNum(kotlin.math.round(mb * 10) / 10), "MB")
    val gb = mb / 1024.0
    return Pair(fmtNum(kotlin.math.round(gb * 100) / 100), "GB")
}

private fun fmtNum(v: Double): String =
    if (v == v.toLong().toDouble()) "${v.toLong()}" else "$v"

// ── Auto-remove period constants (ms) ────────────────────────────────

object AutoRemovePeriod {
    const val NEVER      = 0L
    const val ONE_DAY    = 86_400_000L
    const val ONE_WEEK   = 604_800_000L
    const val ONE_MONTH  = 2_592_000_000L
    const val THREE_MONTHS = 7_776_000_000L
}

object CacheSizeLimit {
    const val GB_1  = 1L * 1024 * 1024 * 1024
    const val GB_2  = 2L * 1024 * 1024 * 1024
    const val GB_5  = 5L * 1024 * 1024 * 1024
    const val GB_10 = 10L * 1024 * 1024 * 1024
    const val GB_16 = 16L * 1024 * 1024 * 1024
    const val GB_32 = 32L * 1024 * 1024 * 1024
    const val GB_64 = 64L * 1024 * 1024 * 1024
    const val NO_LIMIT = -1L

    val presets: List<Long> = listOf(GB_1, GB_2, GB_5, GB_10, GB_16, GB_32, GB_64, NO_LIMIT)
}

class StorageViewModel(
    private val chatRepository: ChatRepository,
    private val settings: Settings
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _totalSize = MutableStateFlow(0L)
    val totalSize: StateFlow<Long> = _totalSize.asStateFlow()

    private val _categories = MutableStateFlow<List<StorageCategoryUi>>(emptyList())
    val categories: StateFlow<List<StorageCategoryUi>> = _categories.asStateFlow()

    private val _selectedCategories = MutableStateFlow<Set<String>>(emptySet())
    val selectedCategories: StateFlow<Set<String>> = _selectedCategories.asStateFlow()

    val selectedSize: StateFlow<Long> = combine(_categories, _selectedCategories) { cats, sel ->
        cats.filter { it.type in sel }.sumOf { it.sizeBytes }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    private val _chatStorageList = MutableStateFlow<List<ChatStorageUi>>(emptyList())

    private val _selectedTab = MutableStateFlow(StorageTab.CHATS)
    val selectedTab: StateFlow<StorageTab> = _selectedTab.asStateFlow()

    val filteredChatList: StateFlow<List<ChatStorageUi>> = combine(
        _chatStorageList, _selectedTab
    ) { list, tab ->
        when (tab) {
            StorageTab.CHATS -> list
            StorageTab.MEDIA -> list.mapNotNull { c ->
                val sz = listOf("photo", "video").sumOf { c.perType[it] ?: 0L }
                if (sz > 0) c.copy(totalSize = sz) else null
            }.sortedByDescending { it.totalSize }
            StorageTab.FILES -> list.mapNotNull { c ->
                val sz = c.perType["file"] ?: 0L
                if (sz > 0) c.copy(totalSize = sz) else null
            }.sortedByDescending { it.totalSize }
            StorageTab.MUSIC -> list.mapNotNull { c ->
                val sz = c.perType["music"] ?: 0L
                if (sz > 0) c.copy(totalSize = sz) else null
            }.sortedByDescending { it.totalSize }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _chatDetailCategories = MutableStateFlow<List<StorageCategoryUi>>(emptyList())
    val chatDetailCategories: StateFlow<List<StorageCategoryUi>> = _chatDetailCategories.asStateFlow()

    private val _chatDetailSelectedCategories = MutableStateFlow<Set<String>>(emptySet())
    val chatDetailSelectedCategories: StateFlow<Set<String>> = _chatDetailSelectedCategories.asStateFlow()

    val chatDetailSelectedSize: StateFlow<Long> = combine(
        _chatDetailCategories, _chatDetailSelectedCategories
    ) { cats, sel ->
        cats.filter { it.type in sel }.sumOf { it.sizeBytes }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    private val _chatDetailTotalSize = MutableStateFlow(0L)
    val chatDetailTotalSize: StateFlow<Long> = _chatDetailTotalSize.asStateFlow()

    private val _chatDetailMediaItems = MutableStateFlow<List<MediaItemInfo>>(emptyList())
    val chatDetailMediaItems: StateFlow<List<MediaItemInfo>> = _chatDetailMediaItems.asStateFlow()

    private val _chatDetailSelectedTab = MutableStateFlow(ChatDetailTab.MEDIA)
    val chatDetailSelectedTab: StateFlow<ChatDetailTab> = _chatDetailSelectedTab.asStateFlow()

    val filteredMediaItems: StateFlow<List<MediaItemInfo>> = combine(
        _chatDetailMediaItems, _chatDetailSelectedTab
    ) { items, tab ->
        when (tab) {
            ChatDetailTab.MEDIA -> items.filter { it.type in listOf("photo", "video") }
            ChatDetailTab.FILES -> items.filter { it.type == "file" }
            ChatDetailTab.MUSIC -> items.filter { it.type == "music" }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _autoRemovePrivateMs = MutableStateFlow(
        settings.getLong("auto_remove_private_ms", AutoRemovePeriod.NEVER)
    )
    val autoRemovePrivateMs: StateFlow<Long> = _autoRemovePrivateMs.asStateFlow()

    private val _autoRemoveGroupMs = MutableStateFlow(
        settings.getLong("auto_remove_group_ms", AutoRemovePeriod.ONE_MONTH)
    )
    val autoRemoveGroupMs: StateFlow<Long> = _autoRemoveGroupMs.asStateFlow()

    private val _maxCacheSizeBytes = MutableStateFlow(
        settings.getLong("max_cache_size_bytes", CacheSizeLimit.NO_LIMIT)
    )
    val maxCacheSizeBytes: StateFlow<Long> = _maxCacheSizeBytes.asStateFlow()

    private val _cleanupStrategy = MutableStateFlow(
        settings.getString("cache_cleanup_strategy", "FIFO")
    )
    val cleanupStrategy: StateFlow<String> = _cleanupStrategy.asStateFlow()

    private val _fifoLimit = MutableStateFlow(settings.getLong("fifo_keep_limit", 1000L))
    val fifoLimit: StateFlow<Long> = _fifoLimit.asStateFlow()
    private val _ttlDays = MutableStateFlow(settings.getLong("ttl_days", 30L))
    val ttlDays: StateFlow<Long> = _ttlDays.asStateFlow()
    private val _lruLimit = MutableStateFlow(settings.getLong("lru_global_limit", 5000L))
    val lruLimit: StateFlow<Long> = _lruLimit.asStateFlow()
    private val _lfuLimit = MutableStateFlow(settings.getLong("lfu_global_limit", 5000L))
    val lfuLimit: StateFlow<Long> = _lfuLimit.asStateFlow()


    fun loadStorageOverview() {
        viewModelScope.launch {
            _isLoading.value = true

            runAutoRemoveCleanup()

            val typeStats = chatRepository.getStorageByType()
            val total = typeStats.sumOf { it.totalSize }
            _totalSize.value = total

            val cats = typeStats.map { stat ->
                StorageCategoryUi(
                    type = stat.type,
                    sizeBytes = stat.totalSize,
                    messageCount = stat.messageCount,
                    percentage = if (total > 0) (stat.totalSize * 100f / total) else 0f,
                    color = colorForType(stat.type),
                    isSelected = stat.type != "text"
                )
            }.sortedByDescending { it.sizeBytes }
            _categories.value = cats
            _selectedCategories.value = cats.filter { it.isSelected }.map { it.type }.toSet()

            val perConvPerType = chatRepository.getStoragePerConversationPerType()
            val grouped = perConvPerType.groupBy { it.conversationId }
            val chatList = grouped.map { (convId, stats) ->
                ChatStorageUi(
                    conversationId = convId,
                    chatName = stats.first().chatName,
                    avatarMediaId = stats.first().avatarMediaId,
                    totalSize = stats.sumOf { it.typeSize },
                    messageCount = stats.sumOf { it.messageCount },
                    perType = stats.associate { it.type to it.typeSize }
                )
            }.sortedByDescending { it.totalSize }
            _chatStorageList.value = chatList

            _isLoading.value = false
        }
    }

    fun loadChatDetail(conversationId: String) {
        viewModelScope.launch {
            _isLoading.value = true

            val typeStats = chatRepository.getStorageByConversationAndType(conversationId)
            val total = typeStats.sumOf { it.totalSize }
            _chatDetailTotalSize.value = total

            val cats = typeStats.map { stat ->
                StorageCategoryUi(
                    type = stat.type,
                    sizeBytes = stat.totalSize,
                    messageCount = stat.messageCount,
                    percentage = if (total > 0) (stat.totalSize * 100f / total) else 0f,
                    color = colorForType(stat.type),
                    isSelected = stat.type != "text"
                )
            }.sortedByDescending { it.sizeBytes }
            _chatDetailCategories.value = cats
            _chatDetailSelectedCategories.value = cats.filter { it.isSelected }.map { it.type }.toSet()

            val media = chatRepository.getMediaItemsByConversation(conversationId)
            _chatDetailMediaItems.value = media

            _isLoading.value = false
        }
    }


    fun toggleCategory(type: String) {
        val current = _selectedCategories.value.toMutableSet()
        if (type in current) current.remove(type) else current.add(type)
        _selectedCategories.value = current
    }

    fun toggleChatDetailCategory(type: String) {
        val current = _chatDetailSelectedCategories.value.toMutableSet()
        if (type in current) current.remove(type) else current.add(type)
        _chatDetailSelectedCategories.value = current
    }


    fun setSelectedTab(tab: StorageTab) { _selectedTab.value = tab }
    fun setChatDetailTab(tab: ChatDetailTab) { _chatDetailSelectedTab.value = tab }


    fun clearSelectedCategories() {
        viewModelScope.launch {
            val selected = _selectedCategories.value
            selected.forEach { type ->
                chatRepository.deleteMessagesByType(type)
            }
            loadStorageOverview()
        }
    }

    fun clearChatDetailSelectedCategories(conversationId: String) {
        viewModelScope.launch {
            val selected = _chatDetailSelectedCategories.value
            selected.forEach { type ->
                chatRepository.deleteMessagesByConversationAndType(conversationId, type)
            }
            loadChatDetail(conversationId)
        }
    }

    fun clearAllLocalData() {
        viewModelScope.launch {
            chatRepository.clearAllLocalData()
            loadStorageOverview()
        }
    }


    fun setAutoRemovePrivate(ms: Long) {
        _autoRemovePrivateMs.value = ms
        settings.putLong("auto_remove_private_ms", ms)
    }

    fun setAutoRemoveGroup(ms: Long) {
        _autoRemoveGroupMs.value = ms
        settings.putLong("auto_remove_group_ms", ms)
    }


    fun setMaxCacheSize(bytes: Long) {
        _maxCacheSizeBytes.value = bytes
        settings.putLong("max_cache_size_bytes", bytes)
    }

    fun setCleanupStrategy(strategy: String) {
        _cleanupStrategy.value = strategy
        settings.putString("cache_cleanup_strategy", strategy)
    }

    fun updateFifoLimit(v: Long) {
        val c = v.coerceIn(100L, 10_000L)
        _fifoLimit.value = c; settings.putLong("fifo_keep_limit", c)
    }
    fun updateTtlDays(v: Long) {
        val c = v.coerceIn(1L, 365L)
        _ttlDays.value = c; settings.putLong("ttl_days", c)
    }
    fun updateLruLimit(v: Long) {
        val c = v.coerceIn(500L, 50_000L)
        _lruLimit.value = c; settings.putLong("lru_global_limit", c)
    }
    fun updateLfuLimit(v: Long) {
        val c = v.coerceIn(500L, 50_000L)
        _lfuLimit.value = c; settings.putLong("lfu_global_limit", c)
    }

    private suspend fun runAutoRemoveCleanup() {
        val now = Clock.System.now().toEpochMilliseconds()
        val privatePeriod = _autoRemovePrivateMs.value
        if (privatePeriod > 0) {
            chatRepository.deleteOldPrivateChatMedia(now - privatePeriod)
        }
        val groupPeriod = _autoRemoveGroupMs.value
        if (groupPeriod > 0) {
            chatRepository.deleteOldGroupChatMedia(now - groupPeriod)
        }
    }
}
