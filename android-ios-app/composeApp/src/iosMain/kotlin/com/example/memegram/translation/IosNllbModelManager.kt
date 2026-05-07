package com.example.memegram.translation

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
class IosNllbModelManager(
    private val httpClient: HttpClient,
    private val modelBaseUrl: String,
) {

    companion object {
        private const val MODEL_DIR_NAME = "nllb-200-distilled-600M"
        private const val PROGRESS_THROTTLE_BYTES = 256L * 1024L
        private const val MIN_PHYSICAL_RAM_BYTES = 3L * 1024L * 1024L * 1024L
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
        val path = "$basePath/translation_models"
        fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    private var cachedEngine: IosNllbTranslationEngine? = null
    private val loadMutex = Mutex()

    suspend fun getEngine(): IosNllbTranslationEngine? {
        loadMutex.withLock { cachedEngine?.let { return it } }

        val modelDir = "$modelsDir/$MODEL_DIR_NAME"
        if (!isModelComplete(modelDir)) {
            val fm = NSFileManager.defaultManager
            val present = listOf("encoder_model.onnx", "decoder_model.onnx", "tokenizer.json")
                .joinToString(", ") { "$it=${fm.fileExistsAtPath("$modelDir/$it")}" }
            println("[NLLB-iOS] model incomplete in $modelDir: $present")
            return null
        }

        if (!hasEnoughMemory()) {
            val physicalGb = NSProcessInfo.processInfo.physicalMemory.toLong() / (1024 * 1024 * 1024)
            println("[NLLB-iOS] memCheck: device has ~${physicalGb} GB RAM, refusing to load NLLB (need ≥ 3 GB)")
            return null
        }

        return loadMutex.withLock {
            cachedEngine?.let { return it }
            try {
                val engine = IosNllbTranslationEngine.load(modelDir)
                cachedEngine = engine
                engine
            } catch (e: Throwable) {
                println("[NLLB-iOS] getEngine failed: ${e::class.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun hasEnoughMemory(): Boolean {
        val physical = NSProcessInfo.processInfo.physicalMemory.toLong()
        return physical >= MIN_PHYSICAL_RAM_BYTES
    }

    fun isModelAvailable(): Boolean = isModelComplete("$modelsDir/$MODEL_DIR_NAME")

    fun getModelSize(): Long {
        val dir = "$modelsDir/$MODEL_DIR_NAME"
        val fm = NSFileManager.defaultManager
        val contents = fm.contentsOfDirectoryAtPath(dir, error = null) ?: return 0L
        var total = 0L
        for (any in contents) {
            val name = any as? String ?: continue
            val path = "$dir/$name"
            val attrs = fm.attributesOfItemAtPath(path, error = null) ?: continue
            val size = attrs["NSFileSize"] as? Number ?: continue
            total += size.toLong()
        }
        return total
    }

    suspend fun deleteModel(): Unit = withContext(Dispatchers.Default) {
        loadMutex.withLock {
            cachedEngine?.close()
            cachedEngine = null
        }
        val dir = "$modelsDir/$MODEL_DIR_NAME"
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
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

        val zipUrl = "${modelBaseUrl.trimEnd('/')}/$MODEL_DIR_NAME.zip"
        val targetDir = "$modelsDir/$MODEL_DIR_NAME"
        val tmpZip = "$modelsDir/$MODEL_DIR_NAME.zip.part"

        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(targetDir)) fm.removeItemAtPath(targetDir, error = null)
        fm.createDirectoryAtPath(targetDir, withIntermediateDirectories = true, attributes = null, error = null)
        if (fm.fileExistsAtPath(tmpZip)) fm.removeItemAtPath(tmpZip, error = null)

        try {
            var lastEmitted = -1L
            httpClient.prepareGet(zipUrl) {
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
                val file = fopen(tmpZip, "wb") ?: error("Cannot open $tmpZip for writing")
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

            IosZipBridge.require().unzip(tmpZip, targetDir)
            fm.removeItemAtPath(tmpZip, error = null)

            if (!isModelComplete(targetDir)) {
                error("Downloaded archive is missing required model files")
            }

            val finalSize = getModelSize()
            send(ModelDownloadProgress(finalSize, finalSize))
        } catch (e: Throwable) {
            fm.removeItemAtPath(targetDir, error = null)
            fm.removeItemAtPath(tmpZip, error = null)
            throw e
        }
    }.flowOn(Dispatchers.Default)

    private fun isModelComplete(dir: String): Boolean {
        val fm = NSFileManager.defaultManager
        val required = listOf("encoder_model.onnx", "decoder_model.onnx", "tokenizer.json")
        return required.all { fm.fileExistsAtPath("$dir/$it") }
    }
}

