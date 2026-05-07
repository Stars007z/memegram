package com.example.memegram.transcription

import com.example.memegram.translation.ModelDownloadProgress
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
class IosWhisperModelManager(
    private val httpClient: HttpClient,
    private val modelBaseUrl: String,
) {

    companion object {
        private const val MODEL_FILE_NAME = "ggml-small-q5_1.bin"
        private const val PROGRESS_THROTTLE_BYTES = 256L * 1024L
        private const val MIN_PHYSICAL_RAM_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MIN_MODEL_FILE_BYTES = 100L * 1024L * 1024L
    }

    private val modelsDir: String = run {
        val fm = NSFileManager.defaultManager
        val urls = fm.URLsForDirectory(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
        )
        val baseUrl = urls.firstOrNull() as? NSURL
            ?: error("Cannot resolve Application Support directory")
        val basePath = baseUrl.path ?: error("Application Support URL has no path")
        val path = "$basePath/whisper_models"
        fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    private val modelPath: String = "$modelsDir/$MODEL_FILE_NAME"

    private var cachedEngine: uniffi.mls_core.WhisperEngine? = null
    private val loadMutex = Mutex()

    suspend fun getEngine(): uniffi.mls_core.WhisperEngine? {
        loadMutex.withLock { cachedEngine?.let { return it } }

        if (!isModelAvailable()) {
            println("[Whisper-iOS] getEngine(): model not on disk at $modelPath")
            return null
        }

        if (!hasEnoughMemory()) {
            val physicalGb = NSProcessInfo.processInfo.physicalMemory.toLong() / (1024 * 1024 * 1024)
            println("[Whisper-iOS] memCheck: device has ~${physicalGb} GB RAM, refusing to load Whisper (need ≥ 2 GB)")
            return null
        }

        return loadMutex.withLock {
            cachedEngine?.let { return it }
            try {
                val engine = uniffi.mls_core.WhisperEngine(modelPath)
                cachedEngine = engine
                println("[Whisper-iOS] getEngine(): whisper model loaded")
                engine
            } catch (e: Throwable) {
                println("[Whisper-iOS] getEngine(): FAILED ${e::class.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun hasEnoughMemory(): Boolean {
        val physical = NSProcessInfo.processInfo.physicalMemory.toLong()
        return physical >= MIN_PHYSICAL_RAM_BYTES
    }

    fun isModelAvailable(): Boolean {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(modelPath)) return false
        val attrs = fm.attributesOfItemAtPath(modelPath, error = null) ?: return false
        val size = (attrs["NSFileSize"] as? Number)?.toLong() ?: 0L
        return size >= MIN_MODEL_FILE_BYTES
    }

    fun getModelSize(): Long {
        val fm = NSFileManager.defaultManager
        val attrs = fm.attributesOfItemAtPath(modelPath, error = null) ?: return 0L
        return (attrs["NSFileSize"] as? Number)?.toLong() ?: 0L
    }

    suspend fun deleteModel(): Unit = withContext(Dispatchers.Default) {
        loadMutex.withLock {
            cachedEngine?.close()
            cachedEngine = null
        }
        NSFileManager.defaultManager.removeItemAtPath(modelPath, error = null)
        Unit
    }

    fun release() {
        cachedEngine?.close()
        cachedEngine = null
    }

    fun downloadModel(): Flow<ModelDownloadProgress> = channelFlow {
        if (isModelAvailable()) {
            val size = getModelSize()
            send(ModelDownloadProgress(size, size))
            return@channelFlow
        }

        val url = "${modelBaseUrl.trimEnd('/')}/$MODEL_FILE_NAME"
        val tmpPath = "$modelPath.part"

        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(tmpPath)) fm.removeItemAtPath(tmpPath, error = null)
        if (fm.fileExistsAtPath(modelPath)) fm.removeItemAtPath(modelPath, error = null)

        try {
            var lastEmitted = -1L
            httpClient.prepareGet(url) {
                onDownload { bytesSentTotal, contentLength ->
                    val total = contentLength ?: -1L
                    if (bytesSentTotal - lastEmitted >= PROGRESS_THROTTLE_BYTES || bytesSentTotal == total) {
                        lastEmitted = bytesSentTotal
                        trySend(ModelDownloadProgress(bytesSentTotal, total))
                    }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
                val channel = response.bodyAsChannel()
                val file = fopen(tmpPath, "wb") ?: error("Cannot open $tmpPath for writing")
                try {
                    val bufferSize = 64L * 1024L
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(bufferSize)
                        val bytes = packet.readByteArray()
                        if (bytes.isEmpty()) continue
                        bytes.usePinned { pinned ->
                            fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
                        }
                    }
                } finally {
                    fclose(file)
                }
            }

            // Atomic rename via NSFileManager.moveItemAtPath
            val moved = fm.moveItemAtPath(tmpPath, toPath = modelPath, error = null)
            if (!moved) error("Failed to rename $tmpPath → $modelPath")

            if (!isModelAvailable()) {
                error("Downloaded file is missing or empty after rename")
            }

            val finalSize = getModelSize()
            send(ModelDownloadProgress(finalSize, finalSize))
        } catch (e: Throwable) {
            fm.removeItemAtPath(tmpPath, error = null)
            fm.removeItemAtPath(modelPath, error = null)
            throw e
        }
    }.flowOn(Dispatchers.Default)
}
