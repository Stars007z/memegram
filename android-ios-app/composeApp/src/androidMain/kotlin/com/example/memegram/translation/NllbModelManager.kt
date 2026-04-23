package com.example.memegram.translation

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
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
import java.util.zip.ZipInputStream

/**
 * Manages the single NLLB-200 translation model.
 *
 * Storage layout: {app_files}/translation_models/nllb-200-distilled-600M/
 *   - encoder_model.onnx
 *   - decoder_model.onnx
 *   - tokenizer.json
 *   - config.json
 *
 * The model is downloaded on demand from Cloudflare R2:
 *   GET {modelBaseUrl}/nllb-200-distilled-600M.zip
 */
class NllbModelManager(
    private val context: Context,
    private val httpClient: HttpClient,
    private val modelBaseUrl: String,
) {

    companion object {
        private const val TAG = "NLLB"
        /**
         * Minimum free RAM (in bytes) required to safely run NLLB translation.
         * With sequential session loading, peak memory is ~700MB (decoder only,
         * encoder is closed before decoder loads). 512MB safety margin.
         */
        private const val MIN_FREE_RAM_BYTES = 512L * 1024 * 1024
        private const val MODEL_DIR_NAME = "nllb-200-distilled-600M"
    }

    private val modelsDir: File = File(context.filesDir, "translation_models")
    private val modelDirName = MODEL_DIR_NAME

    private var cachedEngine: NllbTranslationEngine? = null
    private val loadMutex = Mutex()

    init {
        modelsDir.mkdirs()
    }

    /**
     * Get a ready-to-use translation engine.
     * Returns null if the model is not available, or RAM is too low.
     * Note: this does NOT download — call [downloadModel] explicitly first.
     */
    suspend fun getEngine(): NllbTranslationEngine? {
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

        val modelDir = File(modelsDir, modelDirName)
        if (!isModelComplete(modelDir)) {
            Log.e(TAG, "getEngine(): model files NOT FOUND at ${modelDir.absolutePath}")
            return null
        }
        Log.d(TAG, "getEngine(): model dir = ${modelDir.absolutePath}")

        return loadMutex.withLock {
            cachedEngine?.let { return it }

            if (!hasEnoughMemory()) return null

            try {
                val t0 = System.currentTimeMillis()
                val engine = NllbTranslationEngine.load(modelDir)
                val loadMs = System.currentTimeMillis() - t0
                cachedEngine = engine
                Log.d(TAG, "getEngine(): ONNX sessions loaded in ${loadMs}ms")
                logMemoryState("after model load")
                engine
            } catch (e: Throwable) {
                Log.e(TAG, "getEngine(): FAILED to load: ${e::class.simpleName}: ${e.message}", e)
                null
            }
        }
    }

    /** Check if the model is fully present on disk. */
    fun isModelAvailable(): Boolean {
        return isModelComplete(File(modelsDir, modelDirName))
    }

    /** Approximate model size in bytes. Returns 0 if not downloaded. */
    fun getModelSize(): Long {
        val dir = File(modelsDir, modelDirName)
        if (!dir.exists()) return 0
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    /** Delete the model to free disk space. */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            cachedEngine?.close()
            cachedEngine = null
        }
        val dir = File(modelsDir, modelDirName)
        if (dir.exists()) dir.deleteRecursively()
    }

    /** Release the loaded engine and free native memory. */
    fun release() {
        val hadEngine = cachedEngine != null
        cachedEngine?.close()
        cachedEngine = null
        Log.d(TAG, "release(): hadEngine=$hadEngine")
    }

    fun canLoadModel(): Boolean = isModelAvailable() && hasEnoughMemory()

    /**
     * Stream the NLLB model ZIP from R2 and unzip into [modelsDir].
     * Emits incremental [ModelDownloadProgress]. Cancellation deletes the
     * partially extracted directory.
     *
     * Uses [channelFlow] because Ktor's onDownload callback runs in a
     * different coroutine context than a plain `flow { … }` builder.
     */
    fun downloadModel(): Flow<ModelDownloadProgress> = channelFlow {
        if (isModelAvailable()) {
            val size = getModelSize()
            send(ModelDownloadProgress(size, size))
            return@channelFlow
        }

        val zipUrl = "${modelBaseUrl.trimEnd('/')}/$modelDirName.zip"
        val targetDir = File(modelsDir, modelDirName)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        Log.d(TAG, "downloadModel(): GET $zipUrl")

        try {
            val tmpZip = File(modelsDir, "$modelDirName.zip.part")
            if (tmpZip.exists()) tmpZip.delete()

            var lastEmitted: Long = -1
            httpClient.prepareGet(zipUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    val total = contentLength ?: -1L
                    if (bytesSentTotal - lastEmitted >= 256 * 1024 || bytesSentTotal == total) {
                        lastEmitted = bytesSentTotal
                        trySend(ModelDownloadProgress(bytesSentTotal, total))
                    }
                }
            }.execute { response ->
                response.bodyAsChannel().toInputStream().use { input ->
                    FileOutputStream(tmpZip).use { fos -> input.copyTo(fos) }
                }
            }

            Log.d(TAG, "downloadModel(): download complete (${tmpZip.length()} bytes), extracting…")

            ZipInputStream(tmpZip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, File(entry.name).name)
                    if (!entry.isDirectory) {
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            tmpZip.delete()

            if (!isModelComplete(targetDir)) {
                error("Downloaded archive is missing required model files")
            }

            val finalSize = getModelSize()
            send(ModelDownloadProgress(finalSize, finalSize))
            Log.d(TAG, "downloadModel(): OK, finalSize=$finalSize")
        } catch (e: Throwable) {
            Log.e(TAG, "downloadModel(): FAILED: ${e::class.simpleName}: ${e.message}", e)
            targetDir.deleteRecursively()
            File(modelsDir, "$modelDirName.zip.part").delete()
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

    private fun isModelComplete(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val required = listOf("encoder_model.onnx", "decoder_model.onnx", "tokenizer.json")
        return required.all { File(dir, it).exists() }
    }
}
