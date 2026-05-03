package com.example.memegram.translation

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class NllbTranslationService(
    private val context: Context,
    httpClient: HttpClient,
    modelBaseUrl: String,
) : TranslationService {

    companion object {
        private const val TAG = "NLLB"
    }

    private val modelManager = NllbModelManager(context, httpClient, modelBaseUrl)

    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.2f)
            .build()
    )

    override suspend fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String,
        onProgress: (TranslationProgress) -> Unit,
    ): TranslationResult {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "┌── translate() START ──────────────────────")
        Log.d(TAG, "│ text.length=${text.length}, text='${text.take(80)}'")
        Log.d(TAG, "│ sourceLang=$sourceLang, targetLang=$targetLang")

        onProgress(TranslationProgress(0.04f, TranslationProgressPhase.IDENTIFYING_LANGUAGE))
        val detectedLang = sourceLang ?: identifyLanguage(text) ?: "und"
        Log.d(TAG, "│ detectedLang=$detectedLang (${System.currentTimeMillis() - t0}ms)")

        if (detectedLang == targetLang) {
            Log.d(TAG, "└── SKIP: same language ($detectedLang == $targetLang)")
            return TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
        }

        val srcFlores = NllbLanguageCodes.toFlores(detectedLang)
        val tgtFlores = NllbLanguageCodes.toFlores(targetLang)

        if (srcFlores == null || tgtFlores == null) {
            Log.w(TAG, "└── SKIP: unsupported pair $detectedLang→$targetLang (src=$srcFlores, tgt=$tgtFlores)")
            return TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
        }
        Log.d(TAG, "│ FLORES: $srcFlores → $tgtFlores")

        Log.d(TAG, "│ Loading model...")
        onProgress(TranslationProgress(0.12f, TranslationProgressPhase.LOADING_MODEL))
        val tLoad = System.currentTimeMillis()
        val engine = modelManager.getEngine()
        val loadMs = System.currentTimeMillis() - tLoad
        if (engine == null) {
            Log.e(TAG, "└── FAIL: getEngine() returned null after ${loadMs}ms (low RAM or model missing)")
            return TranslationResult(translatedText = text, detectedSourceLang = detectedLang)
        }
        Log.d(TAG, "│ Model loaded in ${loadMs}ms")

        return try {
            Log.d(TAG, "│ Running inference...")
            val tInf = System.currentTimeMillis()
            val translated = engine.translate(text, srcFlores, tgtFlores, onProgress)
            val infMs = System.currentTimeMillis() - tInf
            Log.d(TAG, "│ Inference done in ${infMs}ms")
            Log.d(TAG, "│ result='${translated.take(80)}'")

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
        }
    }

    override suspend fun identifyLanguage(text: String): String? {
        val scriptHint = ScriptDetector.detect(text)
        if (scriptHint != null && scriptHint.langCode != null && scriptHint.confidence >= 0.7f) {
            return scriptHint.langCode
        }

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
        val available = modelManager.isModelAvailable()
        Log.d(TAG, "ensureModelReady($langCode): modelOnDisk=$available")
    }

    override fun close() {
        modelManager.release()
        languageIdentifier.close()
    }

    override fun isModelAvailable(): Boolean = modelManager.isModelAvailable()

    override fun getModelSize(): Long = modelManager.getModelSize()

    override fun downloadModel(): Flow<ModelDownloadProgress> = modelManager.downloadModel()

    override suspend fun deleteModel() = modelManager.deleteModel()

    override suspend fun releaseModel() {
        modelManager.release()
        Log.d(TAG, "releaseModel(): released (gate hook)")
    }
}
