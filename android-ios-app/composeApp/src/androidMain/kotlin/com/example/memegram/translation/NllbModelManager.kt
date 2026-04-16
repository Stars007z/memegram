package com.example.memegram.translation

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the single NLLB-200 translation model.
 *
 * Unlike the old TranslationModelManager which handled 24+ opus-mt model pairs,
 * this manages ONE model that covers all 200 languages.
 *
 * Model location: {app_files}/translation_models/nllb-200-distilled-600M/
 *   - encoder_model.onnx
 *   - decoder_model.onnx
 *   - tokenizer.json
 *   - config.json
 */
class NllbModelManager(private val context: Context) {

    companion object {
        private const val TAG = "NLLB"
        /**
         * Minimum free RAM (in bytes) required to safely run NLLB translation.
         * With sequential session loading, peak memory is ~700MB (decoder only,
         * encoder is closed before decoder loads). We require 512MB free as a
         * safety margin — the actual session creation will need ~700MB but the
         * system can reclaim file cache pages.
         */
        private const val MIN_FREE_RAM_BYTES = 512L * 1024 * 1024 // 512 MB
    }

    private val modelsDir: File = File(context.filesDir, "translation_models")
    private val modelDirName = "nllb-200-distilled-600M"

    private var cachedEngine: NllbTranslationEngine? = null
    private val loadMutex = Mutex()

    /**
     * Base URL for downloading the model.
     * Expected: {baseUrl}/nllb-200-distilled-600M.zip
     * Set to null to disable downloads (use only pre-installed model).
     */
    var modelBaseUrl: String? = null

    init {
        modelsDir.mkdirs()
    }

    /**
     * Get a ready-to-use translation engine.
     * Returns null if the model is not available, cannot be downloaded,
     * or the device doesn't have enough free RAM to load it safely.
     * Thread-safe: concurrent calls will wait for the first load to finish.
     */
    suspend fun getEngine(): NllbTranslationEngine? {
        Log.d(TAG, "getEngine(): called, cachedEngine=${cachedEngine != null}")

        // Fast path: already loaded
        loadMutex.withLock {
            cachedEngine?.let {
                Log.d(TAG, "getEngine(): returning cached engine")
                return it
            }
        }

        // Check available memory before attempting to load ~300MB model.
        if (!hasEnoughMemory()) {
            Log.e(TAG, "getEngine(): BLOCKED by memory check — not enough free RAM")
            return null
        }

        // Find model directory
        val modelDir = resolveModelDir()
        if (modelDir == null) {
            Log.e(TAG, "getEngine(): model files NOT FOUND on disk")
            return null
        }
        Log.d(TAG, "getEngine(): model dir = ${modelDir.absolutePath}")

        // Load engine (only one thread loads)
        return loadMutex.withLock {
            cachedEngine?.let {
                Log.d(TAG, "getEngine(): returning cached engine (inside lock)")
                return it
            }

            // Re-check memory inside lock (another coroutine may have consumed RAM)
            if (!hasEnoughMemory()) {
                Log.e(TAG, "getEngine(): BLOCKED by memory re-check inside lock")
                return null
            }

            try {
                val t0 = System.currentTimeMillis()
                Log.d(TAG, "getEngine(): loading ONNX sessions...")
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

    /**
     * Check if the model is available on device (ready to load).
     */
    fun isModelAvailable(): Boolean {
        val dir = File(modelsDir, modelDirName)
        return isModelComplete(dir)
    }

    /**
     * Get approximate model size in bytes. Returns 0 if not downloaded.
     */
    fun getModelSize(): Long {
        val dir = File(modelsDir, modelDirName)
        if (!dir.exists()) return 0
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    /**
     * Delete the model to free disk space.
     */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            cachedEngine?.close()
            cachedEngine = null
        }
        val dir = File(modelsDir, modelDirName)
        if (dir.exists()) dir.deleteRecursively()
    }

    /**
     * Release loaded engine and free memory.
     */
    fun release() {
        val hadEngine = cachedEngine != null
        cachedEngine?.close()
        cachedEngine = null
        Log.d(TAG, "release(): hadEngine=$hadEngine")
    }

    /**
     * Check whether there is enough free RAM to load the model.
     * Useful for UI to show "not enough memory" message.
     */
    fun canLoadModel(): Boolean = isModelAvailable() && hasEnoughMemory()

    // ── Internal ─────────────────────────────────────────────────

    /**
     * Check if the device has enough free RAM to safely load the NLLB model.
     * Uses ActivityManager.MemoryInfo to read system-wide available memory.
     */
    private fun hasEnoughMemory(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true // can't check — proceed optimistically
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMB = memInfo.availMem / (1024 * 1024)
        val totalMB = memInfo.totalMem / (1024 * 1024)
        val thresholdMB = MIN_FREE_RAM_BYTES / (1024 * 1024)
        val rt = Runtime.getRuntime()
        val javaMaxMB = rt.maxMemory() / (1024 * 1024)
        val javaUsedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        Log.d(TAG, "memCheck: avail=${availMB}MB / total=${totalMB}MB, " +
                "threshold=${thresholdMB}MB, lowMemory=${memInfo.lowMemory}, " +
                "javaHeap=${javaUsedMB}MB/${javaMaxMB}MB")
        if (memInfo.lowMemory) {
            Log.w(TAG, "memCheck: system reports lowMemory=true → BLOCKED")
            return false
        }
        val ok = memInfo.availMem > MIN_FREE_RAM_BYTES
        if (!ok) {
            Log.w(TAG, "memCheck: ${availMB}MB < ${thresholdMB}MB → BLOCKED")
        }
        return ok
    }

    /**
     * Log current memory state for diagnostics.
     */
    private fun logMemoryState(label: String) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMB = memInfo.availMem / (1024 * 1024)
        val rt = Runtime.getRuntime()
        val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val javaUsedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        Log.d(TAG, "memState[$label]: systemAvail=${availMB}MB, nativeHeap=${nativeHeapMB}MB, javaHeap=${javaUsedMB}MB, lowMem=${memInfo.lowMemory}")
    }

    private suspend fun resolveModelDir(): File? {
        // 1. Internal storage (primary)
        val internalDir = File(modelsDir, modelDirName)
        if (isModelComplete(internalDir)) {
            Log.d(TAG, "resolveModelDir(): found in internal storage: ${internalDir.absolutePath}")
            return internalDir
        }

        // 2. Try downloading
        if (modelBaseUrl != null) {
            try {
                downloadModel()
                if (isModelComplete(internalDir)) return internalDir
            } catch (e: Exception) {
                Log.e(TAG, "resolveModelDir(): download failed: ${e.message}")
            }
        }

        // 3. App-specific external storage (adb push target)
        val appExternalDirs = context.getExternalFilesDirs(null)
        for (base in appExternalDirs) {
            if (base == null) continue
            val dir = File(base, "models/$modelDirName")
            if (isModelComplete(dir)) {
                Log.d(TAG, "resolveModelDir(): found in app external: ${dir.absolutePath}")
                return dir
            }
        }

        // 4. Legacy sdcard path
        val sdcardDir = File("/sdcard/memegram/models/$modelDirName")
        if (isModelComplete(sdcardDir)) {
            Log.d(TAG, "resolveModelDir(): found in sdcard")
            return sdcardDir
        }

        Log.e(TAG, "resolveModelDir(): model NOT FOUND anywhere. Checked:")
        Log.e(TAG, "  internal: ${internalDir.absolutePath} (exists=${internalDir.exists()})")
        appExternalDirs.forEachIndexed { i, base ->
            if (base != null) {
                val dir = File(base, "models/$modelDirName")
                Log.e(TAG, "  external[$i]: ${dir.absolutePath} (exists=${dir.exists()})")
            }
        }
        Log.e(TAG, "  sdcard: ${sdcardDir.absolutePath} (exists=${sdcardDir.exists()})")
        return null
    }

    private fun isModelComplete(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val required = listOf("encoder_model.onnx", "decoder_model.onnx", "tokenizer.json")
        return required.all { File(dir, it).exists() }
    }

    private suspend fun downloadModel() = withContext(Dispatchers.IO) {
        val baseUrl = modelBaseUrl ?: error("Model download URL not configured")
        val zipUrl = "$baseUrl/$modelDirName.zip"
        val modelDir = File(modelsDir, modelDirName)

        println("MemegramDebug [NllbModelManager]: Downloading $zipUrl")

        val url = URL(zipUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000 // 5 minutes for ~300MB

        try {
            if (connection.responseCode != 200) {
                error("HTTP ${connection.responseCode} downloading $zipUrl")
            }

            modelDir.mkdirs()

            ZipInputStream(connection.inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(modelDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            println("MemegramDebug [NllbModelManager]: Downloaded and extracted NLLB model")
        } catch (e: Exception) {
            modelDir.deleteRecursively()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
