package com.example.memegram.translation

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Translation service using NLLB-200-distilled-600M via ONNX Runtime.
 *
 * Architecture:
 *   - Language detection: ScriptDetector (heuristic) → ML Kit Language ID (fallback)
 *   - Translation: NLLB-200 ONNX model (single model for all 200 languages)
 *   - No English pivoting needed (NLLB translates directly between any pair)
 *
 * Compared to the old BergamotTranslationService (opus-mt):
 *   - ONE model (~300MB) instead of 24 pair-specific models (~1.8GB total)
 *   - Much better quality (especially for colloquial text, typos, slang)
 *   - 200 languages instead of ~12
 *   - Direct X→Y translation without going through English
 */
class NllbTranslationService(
    private val context: Context
) : TranslationService {

    companion object {
        private const val TAG = "NLLB"
    }

    private val modelManager = NllbModelManager(context)

    // Serialize translation requests to prevent concurrent ONNX inference
    // from multiplying peak native memory usage (no KV-cache → quadratic growth).
    private val translateMutex = Mutex()

    // ML Kit for language identification of Latin-script text
    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.2f)
            .build()
    )

    override suspend fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String
    ): TranslationResult = translateMutex.withLock {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "┌── translate() START ──────────────────────")
        Log.d(TAG, "│ text.length=${text.length}, text='${text.take(80)}'")
        Log.d(TAG, "│ sourceLang=$sourceLang, targetLang=$targetLang")

        val detectedLang = sourceLang ?: identifyLanguage(text) ?: "und"
        Log.d(TAG, "│ detectedLang=$detectedLang (${System.currentTimeMillis() - t0}ms)")

        // Same language → return original
        if (detectedLang == targetLang) {
            Log.d(TAG, "└── SKIP: same language ($detectedLang == $targetLang)")
            return@withLock TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
        }

        // Map BCP-47 codes to FLORES-200
        val srcFlores = NllbLanguageCodes.toFlores(detectedLang)
        val tgtFlores = NllbLanguageCodes.toFlores(targetLang)

        if (srcFlores == null || tgtFlores == null) {
            Log.w(TAG, "└── SKIP: unsupported pair $detectedLang→$targetLang (src=$srcFlores, tgt=$tgtFlores)")
            return@withLock TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
        }
        Log.d(TAG, "│ FLORES: $srcFlores → $tgtFlores")

        // Get the translation engine (loads model on each call with load-use-release)
        Log.d(TAG, "│ Loading model...")
        val tLoad = System.currentTimeMillis()
        val engine = modelManager.getEngine()
        val loadMs = System.currentTimeMillis() - tLoad
        if (engine == null) {
            Log.e(TAG, "└── FAIL: getEngine() returned null after ${loadMs}ms (low RAM or model missing)")
            return@withLock TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
        }
        Log.d(TAG, "│ Model loaded in ${loadMs}ms")

        try {
            Log.d(TAG, "│ Running inference...")
            val tInf = System.currentTimeMillis()
            val translated = engine.translate(text, srcFlores, tgtFlores)
            val infMs = System.currentTimeMillis() - tInf
            Log.d(TAG, "│ Inference done in ${infMs}ms")
            Log.d(TAG, "│ result='${translated.take(80)}'")

            // Sanity check: if output == input, something went wrong
            if (translated == text || translated.isBlank()) {
                Log.w(TAG, "└── WARN: output == input or blank, returning original (${System.currentTimeMillis() - t0}ms total)")
                TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
            } else {
                Log.d(TAG, "└── OK: translated in ${System.currentTimeMillis() - t0}ms total")
                TranslationResult(translatedText = translated, detectedSourceLang = detectedLang)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "└── ERROR in inference: ${e::class.simpleName}: ${e.message}", e)
            TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
        } finally {
            // ── Load-Use-Release pattern ──
            // Release ONNX sessions after EVERY translation to prevent
            // cumulative native memory buildup → OOM kill.
            modelManager.release()
            System.gc()
            Log.d(TAG, "   [cleanup] Model released, GC requested (${System.currentTimeMillis() - t0}ms since start)")
        }
    }

    override suspend fun identifyLanguage(text: String): String? {
        // 1. ScriptDetector: instant, catches non-Latin scripts
        val scriptHint = ScriptDetector.detect(text)
        if (scriptHint != null && scriptHint.langCode != null && scriptHint.confidence >= 0.7f) {
            return scriptHint.langCode
        }

        // 2. ML Kit Language ID: for Latin scripts (en, de, fr, es, etc.)
        val mlResult = suspendCancellableCoroutine { cont ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { lang ->
                    cont.resume(if (lang == "und") null else lang)
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }

        return mlResult ?: scriptHint?.langCode
    }

    override suspend fun ensureModelReady(langCode: String) {
        // With load-use-release pattern, we don't pre-load the model into memory.
        // Just verify it exists on disk (it will be loaded on demand by translate()).
        val available = modelManager.isModelAvailable()
        Log.d(TAG, "ensureModelReady($langCode): modelOnDisk=$available")
    }

    override fun close() {
        modelManager.release()
        languageIdentifier.close()
    }

    // ── Model management API (for Settings UI) ──────────────────

    /** Check if the NLLB model is downloaded and ready. */
    fun isModelAvailable(): Boolean = modelManager.isModelAvailable()

    /** Get model size in bytes. */
    fun getModelSize(): Long = modelManager.getModelSize()

    /** Delete model to free disk space. */
    suspend fun deleteModel() = modelManager.deleteModel()

    /** Set base URL for model download. */
    fun setModelBaseUrl(url: String?) {
        modelManager.modelBaseUrl = url
    }

    /**
     * Release the loaded ONNX model to free ~400MB of native memory.
     * Unlike [close], this keeps the language identifier alive and allows
     * the model to be reloaded on demand via [translate] → getEngine().
     * Called when the app goes to background.
     */
    fun releaseModel() {
        modelManager.release()
        System.gc()
        Log.d(TAG, "releaseModel(): released (background/trim)")
    }
}
