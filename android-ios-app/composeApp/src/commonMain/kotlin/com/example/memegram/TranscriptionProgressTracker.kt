package com.example.memegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

object TranscriptionProgressTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    private val syntheticJobs = mutableMapOf<String, Job>()
    private val jobsLock = Mutex()

    fun tryQueue(serverId: String): Boolean {
        while (true) {
            val current = _progress.value
            if (serverId in current) return false
            if (_progress.compareAndSet(current, current + (serverId to 0f))) return true
        }
    }

    fun isActive(serverId: String): Boolean = serverId in _activeIds.value

    suspend fun markStarted(serverId: String) {
        _activeIds.update { it + serverId }
        update(serverId, 0.03f)
        startSyntheticProgress(serverId)
    }

    fun update(serverId: String, fraction: Float) {
        _progress.update { current ->
            if (serverId !in current) current
            else current + (serverId to fraction.coerceIn(0f, 0.99f))
        }
    }

    suspend fun finish(serverId: String) {
        stopSyntheticProgress(serverId)
        _progress.update { current ->
            if (serverId in current) current + (serverId to 1f) else current
        }
        delay(220)
        clear(serverId)
    }

    suspend fun clear(serverId: String) {
        stopSyntheticProgress(serverId)
        _activeIds.update { it - serverId }
        _progress.update { it - serverId }
    }

    private suspend fun startSyntheticProgress(serverId: String) {
        jobsLock.withLock {
            if (syntheticJobs[serverId]?.isActive == true) return
            syntheticJobs[serverId] = scope.launch {
                val startedAt = Clock.System.now().toEpochMilliseconds()
                while (isActive) {
                    delay(350)
                    val elapsed = Clock.System.now().toEpochMilliseconds() - startedAt
                    val synthetic = when {
                        elapsed < 1_000L -> 0.08f
                        else -> (0.08f + (elapsed - 1_000L) / 42_000f * 0.84f).coerceAtMost(0.92f)
                    }
                    _progress.update { current ->
                        val actual = current[serverId] ?: return@update current
                        if (actual >= synthetic) current else current + (serverId to synthetic)
                    }
                }
            }
        }
    }

    private suspend fun stopSyntheticProgress(serverId: String) {
        val job = jobsLock.withLock { syntheticJobs.remove(serverId) }
        job?.cancel()
    }
}
