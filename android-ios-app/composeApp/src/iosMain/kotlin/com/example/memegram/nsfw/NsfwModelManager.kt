package com.example.memegram.nsfw

import com.example.memegram.translation.IosOnnxBridge
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
class NsfwModelManager(
    private val httpClient: HttpClient,
    private val modelBaseUrl: String,
) {

    companion object {
        private const val PROGRESS_THROTTLE_BYTES = 256L * 1024L
        private const val MIN_PHYSICAL_RAM_BYTES = 4L * 1024L * 1024L * 1024L

        private val ARTIFACTS = listOf(
            ModelArtifact(
                localName = "nsfw_main_model_fp16.onnx",
                remoteName = "nsfw_main_model_fp16.onnx",
                minBytes = 500L * 1024L * 1024L,
                expectedBytes = 620_721_206L,
            ),
            ModelArtifact(
                localName = "nudenet_320n.onnx",
                remoteName = "320n.onnx",
                minBytes = 10L * 1024L * 1024L,
                expectedBytes = 12_150_158L,
            ),
            ModelArtifact(
                localName = "anime_censor_detect_v1_0_s.onnx",
                remoteName = "model.onnx",
                minBytes = 35L * 1024L * 1024L,
                expectedBytes = 44_586_353L,
            ),
            ModelArtifact(
                localName = "owlv2_swastika.onnx",
                remoteName = "owlv2_swastika.onnx",
                minBytes = 300L * 1024L * 1024L,
                expectedBytes = 364_974_915L,
            ),
        )
    }

    private data class ModelArtifact(
        val localName: String,
        val remoteName: String,
        val minBytes: Long,
        val expectedBytes: Long,
    )

    private val modelsDir: String = run {
        val fm = NSFileManager.defaultManager
        val urls = fm.URLsForDirectory(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
        )
        val baseUrl = urls.firstOrNull() as? NSURL
            ?: error("Cannot resolve Application Support directory")
        val basePath = baseUrl.path ?: error("Application Support URL has no path")
        val path = "$basePath/nsfw_models"
        fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    private var cachedEngine: NsfwCensorEngine? = null
    private val loadMutex = Mutex()

    suspend fun getEngine(): NsfwCensorEngine? {
        loadMutex.withLock { cachedEngine?.let { return it } }

        if (!IosOnnxBridge.isAvailable()) {
            println("[NSFW-iOS] getEngine(): OnnxBridge not registered")
            return null
        }
        if (!hasEnoughMemory()) {
            val physicalGb = NSProcessInfo.processInfo.physicalMemory.toLong() / (1024L * 1024L * 1024L)
            println("[NSFW-iOS] memCheck: device has ~${physicalGb} GB RAM, refusing to load NSFW bundle (need >= 4 GB)")
            return null
        }
        if (!isModelAvailable()) {
            val present = ARTIFACTS.joinToString(", ") { "${it.localName}=${fileSize(artifactPath(it))}" }
            println("[NSFW-iOS] getEngine(): model bundle incomplete in $modelsDir: $present")
            return null
        }

        return loadMutex.withLock {
            cachedEngine?.let { return it }
            try {
                val engine = NsfwCensorEngine.load(
                    mainModelPath = artifactPath(ARTIFACTS[0]),
                    nudeNetModelPath = artifactPath(ARTIFACTS[1]),
                    animeCensorModelPath = artifactPath(ARTIFACTS[2]),
                    owlSwastikaModelPath = artifactPath(ARTIFACTS[3]),
                )
                cachedEngine = engine
                println("[NSFW-iOS] getEngine(): ONNX sessions loaded")
                engine
            } catch (e: Throwable) {
                println("[NSFW-iOS] getEngine(): FAILED ${e::class.simpleName}: ${e.message}")
                null
            }
        }
    }

    fun isModelAvailable(): Boolean = ARTIFACTS.all { artifact ->
        val path = artifactPath(artifact)
        NSFileManager.defaultManager.fileExistsAtPath(path) && fileSize(path) >= artifact.minBytes
    }

    fun getModelSize(): Long = ARTIFACTS.sumOf { fileSize(artifactPath(it)) }

    suspend fun deleteModel(): Unit = withContext(Dispatchers.Default) {
        loadMutex.withLock {
            cachedEngine?.close()
            cachedEngine = null
        }
        val fm = NSFileManager.defaultManager
        ARTIFACTS.forEach { artifact ->
            fm.removeItemAtPath(artifactPath(artifact), error = null)
            fm.removeItemAtPath("${artifactPath(artifact)}.part", error = null)
        }
        Unit
    }

    suspend fun release() {
        loadMutex.withLock {
            cachedEngine?.close()
            cachedEngine = null
            println("[NSFW-iOS] release(): released cached engine")
        }
    }

    fun downloadModel(): Flow<ModelDownloadProgress> = channelFlow {
        if (isModelAvailable()) {
            val size = getModelSize()
            send(ModelDownloadProgress(size, size))
            return@channelFlow
        }

        val fm = NSFileManager.defaultManager
        val expectedTotal = ARTIFACTS.sumOf { it.expectedBytes }
        var completedBytes = 0L

        try {
            ARTIFACTS.forEach { artifact ->
                val targetPath = artifactPath(artifact)
                val tmpPath = "$targetPath.part"
                fm.removeItemAtPath(tmpPath, error = null)
                fm.removeItemAtPath(targetPath, error = null)

                val url = artifactUrl(artifact)
                println("[NSFW-iOS] downloadModel(): GET $url")
                downloadToFile(url, tmpPath) { progress ->
                    trySend(ModelDownloadProgress(completedBytes + progress.bytesDownloaded, expectedTotal))
                }

                val moved = fm.moveItemAtPath(tmpPath, toPath = targetPath, error = null)
                if (!moved) error("Failed to rename $tmpPath to $targetPath")
                if (fileSize(targetPath) < artifact.minBytes) {
                    error("Downloaded ${artifact.localName} is missing or too small")
                }

                completedBytes += fileSize(targetPath)
                send(ModelDownloadProgress(completedBytes, expectedTotal))
            }

            val finalSize = getModelSize()
            send(ModelDownloadProgress(finalSize, finalSize))
            println("[NSFW-iOS] downloadModel(): OK, finalSize=$finalSize")
        } catch (e: Throwable) {
            ARTIFACTS.forEach { artifact ->
                fm.removeItemAtPath(artifactPath(artifact), error = null)
                fm.removeItemAtPath("${artifactPath(artifact)}.part", error = null)
            }
            throw e
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun downloadToFile(
        url: String,
        targetPath: String,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        var lastEmitted = -1L
        httpClient.prepareGet(url) {
            onDownload { bytesSentTotal, contentLength ->
                val total = contentLength ?: -1L
                if (bytesSentTotal - lastEmitted >= PROGRESS_THROTTLE_BYTES || bytesSentTotal == total) {
                    lastEmitted = bytesSentTotal
                    onProgress(ModelDownloadProgress(bytesSentTotal, total))
                }
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                error("HTTP ${response.status.value}: ${response.status.description}")
            }
            val channel = response.bodyAsChannel()
            val file = fopen(targetPath, "wb") ?: error("Cannot open $targetPath for writing")
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
    }

    private fun artifactPath(artifact: ModelArtifact): String = "$modelsDir/${artifact.localName}"

    private fun artifactUrl(artifact: ModelArtifact): String = "${modelBaseUrl.trimEnd('/')}/${artifact.remoteName}"

    private fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0L
        return (attrs["NSFileSize"] as? Number)?.toLong() ?: 0L
    }

    private fun hasEnoughMemory(): Boolean {
        val physical = NSProcessInfo.processInfo.physicalMemory.toLong()
        return physical >= MIN_PHYSICAL_RAM_BYTES
    }
}
