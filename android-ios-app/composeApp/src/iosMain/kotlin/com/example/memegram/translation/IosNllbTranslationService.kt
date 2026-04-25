package com.example.memegram.translation

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IosNllbTranslationService(
    httpClient: HttpClient,
    modelBaseUrl: String,
) : TranslationService {

    private val modelManager = IosNllbModelManager(httpClient, modelBaseUrl)
    private val translateMutex = Mutex()

    override suspend fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String,
    ): TranslationResult = translateMutex.withLock {
        val detectedLang = sourceLang ?: identifyLanguage(text) ?: "und"
        println("[NLLB-iOS] translate: text='${text.take(40)}' src=$sourceLang detected=$detectedLang tgt=$targetLang")

        if (detectedLang == targetLang) {
            return TranslationResult(text, detectedLang)
        }

        val srcFlores = NllbLanguageCodes.toFlores(detectedLang)
        val tgtFlores = NllbLanguageCodes.toFlores(targetLang)
        if (srcFlores == null || tgtFlores == null) {
            println("[NLLB-iOS] translate: FLORES code missing — srcFlores=$srcFlores tgtFlores=$tgtFlores (detected=$detectedLang tgt=$targetLang) — returning original")
            return TranslationResult(text, detectedLang)
        }

        if (!IosOnnxBridge.isAvailable()) {
            println("[NLLB-iOS] OnnxBridge not registered — translation skipped")
            return TranslationResult(text, detectedLang)
        }

        val engine = modelManager.getEngine()
        if (engine == null) {
            println("[NLLB-iOS] translate: engine is null (model not on disk or not enough RAM) — returning original")
            return TranslationResult(text, detectedLang)
        }

        return try {
            println("[NLLB-iOS] translate: starting inference srcFlores=$srcFlores tgtFlores=$tgtFlores")
            val translated = engine.translate(text, srcFlores, tgtFlores)
            println("[NLLB-iOS] translate: inference done, output='${translated.take(40)}'")
            if (translated.isBlank() || translated == text) {
                TranslationResult(text, detectedLang)
            } else {
                TranslationResult(translated, detectedLang)
            }
        } catch (e: Throwable) {
            println("[NLLB-iOS] inference error: ${e::class.simpleName}: ${e.message}")
            TranslationResult(text, detectedLang)
        }
    }

    override suspend fun identifyLanguage(text: String): String? {
        val scriptHint = ScriptDetector.detect(text)
        println("[NLLB-iOS] identifyLanguage: scriptHint=${scriptHint?.script} langCode=${scriptHint?.langCode} confidence=${scriptHint?.confidence}")
        if (scriptHint != null && scriptHint.langCode != null && scriptHint.confidence >= 0.7f) {
            return scriptHint.langCode
        }
        val delegate = IosLanguageIdBridge.delegate
        val nl = delegate?.identify(text)
        if (delegate == null) {
            println("[NLLB-iOS] identifyLanguage: ⚠️ IosLanguageIdBridge delegate is NULL — Swift side did not register.")
        } else {
            println("[NLLB-iOS] identifyLanguage: NLLanguageRecognizer returned '$nl'")
        }
        if (!nl.isNullOrBlank() && nl != "und") return nl

        if (scriptHint?.script == "Latin") {
            println("[NLLB-iOS] identifyLanguage: Latin script + recognizer failed → fallback to 'en'")
            return "en"
        }
        return scriptHint?.langCode
    }

    override suspend fun ensureModelReady(langCode: String) {
        modelManager.isModelAvailable()
    }

    override fun close() {
        modelManager.release()
    }

    override fun isModelAvailable(): Boolean = modelManager.isModelAvailable()
    override fun getModelSize(): Long = modelManager.getModelSize()
    override fun downloadModel(): Flow<ModelDownloadProgress> = modelManager.downloadModel()
    override suspend fun deleteModel() = modelManager.deleteModel()

    override suspend fun releaseModel() {
        modelManager.release()
        println("[NLLB-iOS] releaseModel(): released (gate hook)")
    }
}
