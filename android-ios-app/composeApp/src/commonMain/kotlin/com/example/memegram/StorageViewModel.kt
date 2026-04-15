package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.repository.ChatRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StorageViewModel(
    private val chatRepository: ChatRepository,
    private val settings: Settings
) : ViewModel() {

    private val _cleanupStrategy = MutableStateFlow(
        settings.getString("cache_cleanup_strategy", "FIFO")
    )
    val cleanupStrategy: StateFlow<String> = _cleanupStrategy.asStateFlow()

    private val _fifoLimit = MutableStateFlow(settings.getLong("fifo_keep_limit", 1000L))
    val fifoLimit: StateFlow<Long> = _fifoLimit.asStateFlow()

    private val _ttlDays   = MutableStateFlow(settings.getLong("ttl_days", 30L))
    val ttlDays: StateFlow<Long>   = _ttlDays.asStateFlow()

    private val _lruLimit  = MutableStateFlow(settings.getLong("lru_global_limit", 5000L))
    val lruLimit: StateFlow<Long>  = _lruLimit.asStateFlow()

    private val _lfuLimit  = MutableStateFlow(settings.getLong("lfu_global_limit", 5000L))
    val lfuLimit: StateFlow<Long>  = _lfuLimit.asStateFlow()

    fun setCleanupStrategy(strategy: String) {
        _cleanupStrategy.value = strategy
        settings.putString("cache_cleanup_strategy", strategy)
    }

    fun clearCache() {
        viewModelScope.launch {
            chatRepository.clearAllLocalData()
        }
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
}