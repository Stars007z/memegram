package com.example.memegram.nsfw

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.memegram.translation.ModelDownloadProgress
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class NsfwModelManager(
    private val context: Context,
    private val httpClient: HttpClient,
    private val modelBaseUrl: String,
) {

    companion object {
        private const val TAG = "NSFW"
        private const val MIN_FREE_RAM_BYTES = 1_500L * 1024 * 1024

        private val ARTIFACTS = listOf(
            ModelArtifact(
                localName = "nsfw_main_model_fp16.onnx",
                remoteName = "nsfw_main_model_fp16.onnx",
                minBytes = 500L * 1024 * 1024,
                expectedBytes = 620_721_206L,
            ),
            ModelArtifact(
                localName = "nudenet_320n.onnx",
                remoteName = "320n.onnx",
                minBytes = 10L * 1024 * 1024,
                expectedBytes = 12_150_158L,
            ),
            ModelArtifact(
                localName = "anime_censor_detect_v1_0_s.onnx",
                remoteName = "model.onnx",
                minBytes = 35L * 1024 * 1024,
                expectedBytes = 44_586_353L,
            ),
            ModelArtifact(
                localName = "owlv2_swastika.onnx",
                remoteName = "owlv2_swastika.onnx",
                minBytes = 300L * 1024 * 1024,
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

    private val modelsDir: File = File(context.filesDir, "nsfw_models")
    private var cachedEngine: NsfwCensorEngine? = null
    private val loadMutex = Mutex()

    init {
        modelsDir.mkdirs()
    }

    suspend fun getEngine(): NsfwCensorEngine? {
        loadMutex.withLock {
            cachedEngine?.let { return it }
        }

        if (!hasEnoughMemory()) {
            Log.w(TAG, "getEngine(): not enough free RAM")
            return null
        }
        if (!isModelAvailable()) {
            Log.w(TAG, "getEngine(): NSFW model bundle incomplete in ${modelsDir.absolutePath}")
            return null
        }

        return loadMutex.withLock {
            cachedEngine?.let { return it }
            if (!hasEnoughMemory()) return null
            try {
                val t0 = System.currentTimeMillis()
                val engine = NsfwCensorEngine.load(
                    mainModelFile = artifactFile(ARTIFACTS[0]),
                    nudeNetModelFile = artifactFile(ARTIFACTS[1]),
                    animeCensorModelFile = artifactFile(ARTIFACTS[2]),
                    owlSwastikaModelFile = artifactFile(ARTIFACTS[3]),
                )
                cachedEngine = engine
                Log.d(TAG, "getEngine(): engine ready in ${System.currentTimeMillis() - t0}ms (sessions load per phase)")
                logMemoryState("after engine init")
                engine
            } catch (e: Throwable) {
                Log.e(TAG, "getEngine(): failed to load model: ${e::class.simpleName}: ${e.message}", e)
                null
            }
        }
    }

    fun isModelAvailable(): Boolean {
        return ARTIFACTS.all { artifact ->
            val file = artifactFile(artifact)
            file.exists() && file.length() >= artifact.minBytes
        }
    }

    fun getModelSize(): Long {
        return ARTIFACTS.sumOf { artifactFile(it).takeIf { file -> file.exists() }?.length() ?: 0L }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            runCatching { cachedEngine?.close() }
            cachedEngine = null
        }
        ARTIFACTS.forEach { artifactFile(it).delete() }
    }

    suspend fun release() {
        loadMutex.withLock {
            val engine = cachedEngine
            cachedEngine = null
            runCatching { engine?.close() }
            Log.d(TAG, "release(): hadEngine=${engine != null}")
        }
    }

    fun downloadModel(): Flow<ModelDownloadProgress> = channelFlow {
        if (isModelAvailable()) {
            val size = getModelSize()
            send(ModelDownloadProgress(size, size))
            return@channelFlow
        }

        try {
            var completedBytes = 0L
            val expectedTotal = ARTIFACTS.sumOf { it.expectedBytes }

            ARTIFACTS.forEach { artifact ->
                val targetFile = artifactFile(artifact)
                val tmpFile = File(modelsDir, "${artifact.localName}.part")
                tmpFile.delete()
                targetFile.delete()

                val url = artifactUrl(artifact)
                Log.d(TAG, "downloadModel(): GET $url")
                downloadToFile(url, tmpFile) { progress ->
                    trySend(ModelDownloadProgress(completedBytes + progress.bytesDownloaded, expectedTotal))
                }

                if (!tmpFile.renameTo(targetFile)) {
                    error("Failed to rename ${tmpFile.absolutePath} to ${targetFile.absolutePath}")
                }
                if (!targetFile.exists() || targetFile.length() < artifact.minBytes) {
                    error("Downloaded ${artifact.localName} is missing or too small")
                }
                completedBytes += targetFile.length()
                send(ModelDownloadProgress(completedBytes, expectedTotal))
            }

            val finalSize = getModelSize()
            send(ModelDownloadProgress(finalSize, finalSize))
            Log.d(TAG, "downloadModel(): OK, finalSize=$finalSize")
        } catch (e: Throwable) {
            Log.e(TAG, "downloadModel(): failed: ${e::class.simpleName}: ${e.message}", e)
            ARTIFACTS.forEach {
                artifactFile(it).delete()
                File(modelsDir, "${it.localName}.part").delete()
            }
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadToFile(
        url: String,
        target: File,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        val attemptedUrls = listOfNotNull(url, legacyFallbackUrl(url)).distinct()
        var lastError: Throwable? = null
        for (candidateUrl in attemptedUrls) {
            runCatching {
                downloadToFileOnce(candidateUrl, target, onProgress)
                return
            }.onFailure { e ->
                lastError = e
                target.delete()
                Log.w(TAG, "downloadToFile(): failed $candidateUrl: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Download failed: $url")
    }

    private suspend fun downloadToFileOnce(
        url: String,
        target: File,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        var lastEmitted: Long = -1
        httpClient.prepareGet(url) {
            onDownload { bytesSentTotal, contentLength ->
                val total = contentLength ?: -1L
                if (bytesSentTotal - lastEmitted >= 256 * 1024 || bytesSentTotal == total) {
                    lastEmitted = bytesSentTotal
                    onProgress(ModelDownloadProgress(bytesSentTotal, total))
                }
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                error("HTTP ${response.status.value}: ${response.status.description}")
            }
            response.bodyAsChannel().toInputStream().use { input ->
                FileOutputStream(target).use { fos -> input.copyTo(fos) }
            }
        }
    }

    private fun artifactFile(artifact: ModelArtifact): File = File(modelsDir, artifact.localName)

    private fun artifactUrl(artifact: ModelArtifact): String {
        val base = modelBaseUrl.trimEnd('/')
        return "$base/${artifact.remoteName}"
    }

    private fun legacyFallbackUrl(primaryUrl: String): String? {
        return null
    }

    private fun hasEnoughMemory(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        if (memInfo.lowMemory) return false
        return memInfo.availMem > MIN_FREE_RAM_BYTES
    }

    private fun logMemoryState(label: String) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMB = memInfo.availMem / (1024 * 1024)
        val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val rt = Runtime.getRuntime()
        val javaUsedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        Log.d(TAG, "memState[$label]: systemAvail=${availMB}MB, nativeHeap=${nativeHeapMB}MB, javaHeap=${javaUsedMB}MB")
    }
}
