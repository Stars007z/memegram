package com.example.memegram.ml

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException

object MlModelGate {

    enum class Priority { USER, AUTO }

    private const val IDLE_TIMEOUT_MS = 60_000L
    private const val AUTO_QUEUE_SOFT_CAP = 8

    private class Task<R>(
        val block: suspend () -> R,
        val onStarted: (suspend () -> Unit)?,
        val deferred: CompletableDeferred<R>,
    )

    private val userQueue = Channel<Task<*>>(Channel.UNLIMITED)
    private val autoQueue = Channel<Task<*>>(Channel.UNLIMITED)

    private val autoLock = Mutex()
    private var autoQueued: ArrayDeque<Task<*>> = ArrayDeque()

    @Volatile
    private var releaseHooks: List<suspend () -> Unit> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null
    private var idleJob: Job? = null
    private val activeModelLock = Mutex()
    @Volatile
    private var activityGeneration: Long = 0L

    init { startWorker() }

    fun setReleaseHook(hook: suspend () -> Unit) {
        releaseHooks = releaseHooks + hook
    }

    suspend fun <R> withExclusiveModelAccess(block: suspend () -> R): R =
        activeModelLock.withLock { block() }

    suspend fun <R> withModel(
        priority: Priority = Priority.USER,
        onStarted: (suspend () -> Unit)? = null,
        block: suspend () -> R,
    ): R {
        val deferred = CompletableDeferred<R>()
        val task = Task(block, onStarted, deferred)
        when (priority) {
            Priority.USER -> userQueue.send(task)
            Priority.AUTO -> {
                val dropped: Task<*>? = autoLock.withLock {
                    autoQueued.addLast(task)
                    if (autoQueued.size > AUTO_QUEUE_SOFT_CAP) autoQueued.removeFirst() else null
                }
                dropped?.deferred?.cancel(CancellationException("Dropped by MlModelGate (auto queue overflow)"))
                autoQueue.send(task)
            }
        }
        try {
            return deferred.await()
        } catch (ce: CancellationException) {
            deferred.cancel(ce)
            throw ce
        }
    }

    fun onMemoryPressure(cancelQueuedAuto: Boolean = true) {
        if (cancelQueuedAuto) {
            scope.launch { cancelAllAuto(reason = "Memory pressure") }
        }
        scope.launch { triggerRelease() }
    }

    fun onAppBackgrounded() {
        scope.launch { triggerRelease() }
    }

    private suspend fun cancelAllAuto(reason: String) {
        autoLock.withLock {
            val pending = autoQueued.toList()
            autoQueued.clear()
            pending.forEach { it.deferred.cancel(CancellationException(reason)) }
        }
        while (true) {
            val r = autoQueue.tryReceive()
            if (!r.isSuccess) break
        }
    }

    private suspend fun triggerRelease(expectedGeneration: Long? = null) {
        activeModelLock.withLock {
            if (expectedGeneration != null && expectedGeneration != activityGeneration) return@withLock
            val hooks = releaseHooks
            for (hook in hooks) {
                try { hook.invoke() } catch (_: Throwable) { /* swallow */ }
            }
        }
    }

    private fun startWorker() {
        workerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                val task: Task<*> = pickNextTask()
                idleJob?.cancel()
                runTask(task)
                val idleGeneration = activityGeneration
                idleJob = scope.launch {
                    delay(IDLE_TIMEOUT_MS)
                    triggerRelease(idleGeneration)
                }
            }
        }
    }

    private suspend fun pickNextTask(): Task<*> {
        userQueue.tryReceive().getOrNull()?.let { return it }
        autoQueue.tryReceive().getOrNull()?.let { return removeFromAutoBookkeeping(it) }
        return select {
            userQueue.onReceive { it }
            autoQueue.onReceive { removeFromAutoBookkeeping(it) }
        }
    }

    private suspend fun removeFromAutoBookkeeping(task: Task<*>): Task<*> {
        autoLock.withLock { autoQueued.remove(task) }
        return task
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <R> runTask(task: Task<R>) {
        if (task.deferred.isCancelled) return
        try {
            val result = activeModelLock.withLock {
                if (task.deferred.isCancelled) throw CancellationException("Cancelled before model start")
                activityGeneration += 1L
                try {
                    task.onStarted?.invoke()
                    task.block()
                } finally {
                    activityGeneration += 1L
                }
            }
            task.deferred.complete(result)
        } catch (ce: CancellationException) {
            task.deferred.cancel(ce)
        } catch (t: Throwable) {
            task.deferred.completeExceptionally(t)
        }
    }

    suspend fun shutdownForTest() {
        workerJob?.cancelAndJoin()
        idleJob?.cancelAndJoin()
    }
}
