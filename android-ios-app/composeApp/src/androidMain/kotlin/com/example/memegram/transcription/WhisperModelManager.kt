package com.example.memegram.transcription

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

class WhisperModelManager(
    private val context: Context,
    private val httpClient: HttpClient,
    private val modelBaseUrl: String,
) {

    companion object {
        private const val TAG = "Whisper"
        private const val MIN_FREE_RAM_BYTES = 700L * 1024 * 1024
        private const val MODEL_FILE_NAME = "ggml-small-q5_1.bin"
        private const val MIN_MODEL_FILE_BYTES = 100L * 1024 * 1024
    }

    private val modelsDir: File = File(context.filesDir, "whisper_models")
    private val modelFile: File = File(modelsDir, MODEL_FILE_NAME)

    private var cachedEngine: uniffi.mls_core.WhisperEngine? = null
    private val loadMutex = Mutex()

    init {
        modelsDir.mkdirs()
    }

    suspend fun getEngine(): uniffi.mls_core.WhisperEngine? {
        Log.d(TAG, "getEngine(): called, cachedEngine=${cachedEngine != null}")

        loadMutex.withLock {
            cachedEngine?.let {
                Log.d(TAG, "getEngine(): returning cached engine")
                return it
            }
        }

        if (!hasEnoughMemory()) {
            Log.e(TAG, "getEngine(): BLOCKED by memory check — not enough free RAM")
            return null
        }

        if (!isModelAvailable()) {
            Log.e(TAG, "getEngine(): model file NOT FOUND at ${modelFile.absolutePath}")
            return null
        }
        Log.d(TAG, "getEngine(): model file = ${modelFile.absolutePath}")

        return loadMutex.withLock {
            cachedEngine?.let { return it }

            if (!hasEnoughMemory()) return null

            try {
                val t0 = System.currentTimeMillis()
                val engine = uniffi.mls_core.WhisperEngine(modelFile.absolutePath)
                val loadMs = System.currentTimeMillis() - t0
                cachedEngine = engine
                Log.d(TAG, "getEngine(): whisper model loaded in ${loadMs}ms")
                logMemoryState("after model load")
                engine
            } catch (e: Throwable) {
                Log.e(TAG, "getEngine(): FAILED to load: ${e::class.simpleName}: ${e.message}", e)
                null
            }
        }
    }

    fun isModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() >= MIN_MODEL_FILE_BYTES
    }

    fun getModelSize(): Long {
        return if (modelFile.exists()) modelFile.length() else 0
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            cachedEngine?.close()
            cachedEngine = null
        }
        if (modelFile.exists()) modelFile.delete()
    }

    fun release() {
        val hadEngine = cachedEngine != null
        cachedEngine?.close()
        cachedEngine = null
        Log.d(TAG, "release(): hadEngine=$hadEngine")
    }

    fun canLoadModel(): Boolean = isModelAvailable() && hasEnoughMemory()

    fun downloadModel(): Flow<ModelDownloadProgress> = channelFlow {
        if (isModelAvailable()) {
            val size = getModelSize()
            send(ModelDownloadProgress(size, size))
            return@channelFlow
        }

        val url = "${modelBaseUrl.trimEnd('/')}/$MODEL_FILE_NAME"
        val tmpFile = File(modelsDir, "$MODEL_FILE_NAME.part")
        if (tmpFile.exists()) tmpFile.delete()
        if (modelFile.exists()) modelFile.delete()

        Log.d(TAG, "downloadModel(): GET $url")

        try {
            var lastEmitted: Long = -1
            httpClient.prepareGet(url) {
                onDownload { bytesSentTotal, contentLength ->
                    val total = contentLength ?: -1L
                    if (bytesSentTotal - lastEmitted >= 256 * 1024 || bytesSentTotal == total) {
                        lastEmitted = bytesSentTotal
                        trySend(ModelDownloadProgress(bytesSentTotal, total))
                    }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
                response.bodyAsChannel().toInputStream().use { input ->
                    FileOutputStream(tmpFile).use { fos -> input.copyTo(fos) }
                }
            }

            Log.d(TAG, "downloadModel(): download complete (${tmpFile.length()} bytes), finalizing…")

            if (!tmpFile.renameTo(modelFile)) {
                error("Failed to rename ${tmpFile.absolutePath} → ${modelFile.absolutePath}")
            }

            if (!isModelAvailable()) {
                error("Downloaded file is missing or empty after rename")
            }

            val finalSize = getModelSize()
            send(ModelDownloadProgress(finalSize, finalSize))
            Log.d(TAG, "downloadModel(): OK, finalSize=$finalSize")
        } catch (e: Throwable) {
            Log.e(TAG, "downloadModel(): FAILED: ${e::class.simpleName}: ${e.message}", e)
            tmpFile.delete()
            modelFile.delete()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun hasEnoughMemory(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        if (memInfo.lowMemory) {
            Log.w(TAG, "memCheck: system reports lowMemory=true → BLOCKED")
            return false
        }
        val ok = memInfo.availMem > MIN_FREE_RAM_BYTES
        if (!ok) {
            Log.w(TAG, "memCheck: ${memInfo.availMem / 1024 / 1024}MB < ${MIN_FREE_RAM_BYTES / 1024 / 1024}MB → BLOCKED")
        }
        return ok
    }

    private fun logMemoryState(label: String) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMB = memInfo.availMem / (1024 * 1024)
        val rt = Runtime.getRuntime()
        val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val javaUsedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        Log.d(TAG, "memState[$label]: systemAvail=${availMB}MB, nativeHeap=${nativeHeapMB}MB, javaHeap=${javaUsedMB}MB")
    }
}
