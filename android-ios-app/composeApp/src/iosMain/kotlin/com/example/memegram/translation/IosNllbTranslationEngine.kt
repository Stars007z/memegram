package com.example.memegram.translation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

class IosNllbTranslationEngine private constructor(
    private val encoderPath: String,
    private val decoderPath: String,
    private val tokenizer: NllbTokenizer,
    private val maxLength: Int,
) {
    companion object {
        suspend fun load(modelDir: String): IosNllbTranslationEngine = withContext(Dispatchers.Default) {
            val fm = NSFileManager.defaultManager
            val encoderPath = "$modelDir/encoder_model.onnx"
            val decoderPath = "$modelDir/decoder_model.onnx"
            val tokenizerPath = "$modelDir/tokenizer.json"
            val configPath = "$modelDir/config.json"

            require(fm.fileExistsAtPath(encoderPath)) { "encoder_model.onnx not found in $modelDir" }
            require(fm.fileExistsAtPath(decoderPath)) { "decoder_model.onnx not found in $modelDir" }
            require(fm.fileExistsAtPath(tokenizerPath)) { "tokenizer.json not found in $modelDir" }

            val tokenizerJson = readUtf8File(tokenizerPath)
                ?: error("Cannot read $tokenizerPath")
            val configJson = if (fm.fileExistsAtPath(configPath)) readUtf8File(configPath) else null
            val tokenizer = NllbTokenizer.fromJson(tokenizerJson, configJson)

            var maxLength = 128
            if (configJson != null) {
                try {
                    val parsed = Json.parseToJsonElement(configJson).jsonObject
                    maxLength = (parsed["max_length"]?.jsonPrimitive?.int ?: 128).coerceAtMost(128)
                } catch (_: Throwable) { }
            }

            IosNllbTranslationEngine(encoderPath, decoderPath, tokenizer, maxLength)
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readUtf8File(path: String): String? {
            return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, error = null) as String?
        }
    }

    fun isLanguageSupported(floresCode: String): Boolean =
        tokenizer.getLangTokenId(floresCode) != null

    private var encoderSession: Long = 0L
    private var decoderSession: Long = 0L

    private val translateMutex = Mutex()

    fun close() {
        if (!translateMutex.tryLock()) return
        try {
        val bridge = IosOnnxBridge.delegate ?: return
        if (encoderSession != 0L) {
            bridge.closeSession(encoderSession)
            encoderSession = 0L
        }
        if (decoderSession != 0L) {
            bridge.closeSession(decoderSession)
            decoderSession = 0L
        }
        } finally {
            translateMutex.unlock()
        }
    }

    private fun ensureSession(bridge: OnnxBridgeDelegate, path: String, currentHandle: Long): Long {
        if (currentHandle != 0L) return currentHandle
        val handle = bridge.loadSession(path)
        if (handle == 0L) {
            val why = bridge.lastLoadError ?: "unknown error"
            error("ORT loadSession failed for $path: $why")
        }
        return handle
    }

    suspend fun translate(
        text: String,
        srcLangFlores: String,
        tgtLangFlores: String,
    ): String = translateMutex.withLock {
        withContext(Dispatchers.Default) {
        val sentences = if (text.length > 300) splitSentences(text) else listOf(text)

        data class Tokenized(
            val inputIds: LongArray,
            val attentionMask: LongArray,
            val decoderStartIds: List<Int>,
        )

        val tokenized = sentences.map { s ->
            val ids = tokenizer.encode(s, srcLangFlores)
            val idsLong = LongArray(ids.size) { ids[it].toLong() }
            val mask = LongArray(ids.size) { 1L }
            val startIds = tokenizer.decoderStartIds(tgtLangFlores)
            Tokenized(idsLong, mask, startIds)
        }

        val bridge = IosOnnxBridge.require()

        data class Encoded(val hidden: FloatArray, val shape: LongArray, val mask: LongArray)

        val encoded: List<Encoded> = try {
            encoderSession = ensureSession(bridge, encoderPath, encoderSession)
            tokenized.map { tok ->
                val seqLen = tok.inputIds.size.toLong()
                val outputs = bridge.run(
                    handle = encoderSession,
                    int64Names = arrayOf("input_ids", "attention_mask"),
                    int64Data = arrayOf(tok.inputIds, tok.attentionMask),
                    int64Shapes = arrayOf(longArrayOf(1, seqLen), longArrayOf(1, seqLen)),
                    floatNames = emptyArray(),
                    floatData = emptyArray(),
                    floatShapes = emptyArray(),
                    outputNames = arrayOf("last_hidden_state"),
                )
                val out = outputs[0]
                Encoded(out.data, out.shape, tok.attentionMask)
            }
        } finally {
            if (encoderSession != 0L) {
                bridge.closeSession(encoderSession)
                encoderSession = 0L
            }
        }

        val results: List<String> = try {
            decoderSession = ensureSession(bridge, decoderPath, decoderSession)
            tokenized.zip(encoded).map { (tok, enc) ->
                val ids = decodeAutoregressive(bridge, decoderSession, tok.decoderStartIds, enc.hidden, enc.shape, enc.mask)
                bridge.clearPersistentInputs(decoderSession)
                tokenizer.decode(ids)
            }
        } finally {
            if (decoderSession != 0L) {
                bridge.clearPersistentInputs(decoderSession)
                bridge.closeSession(decoderSession)
                decoderSession = 0L
            }
        }

        results.joinToString(" ")
        }
    }

    private fun decodeAutoregressive(
        bridge: OnnxBridgeDelegate,
        session: Long,
        decoderStartIds: List<Int>,
        hiddenData: FloatArray,
        hiddenShape: LongArray,
        encoderAttentionMask: LongArray,
    ): List<Int> {
        val generated = mutableListOf<Int>()
        val decoderInputIds = decoderStartIds.toMutableList()
        val srcSeqLen = encoderAttentionMask.size.toLong()
        val vocabSize = tokenizer.vocabSize

        check(bridge.setPersistentFloatInput(session, "encoder_hidden_states", hiddenData, hiddenShape)) {
            "ORT setPersistentFloatInput(encoder_hidden_states) failed"
        }
        check(bridge.setPersistentInt64Input(session, "encoder_attention_mask", encoderAttentionMask, longArrayOf(1, srcSeqLen))) {
            "ORT setPersistentInt64Input(encoder_attention_mask) failed"
        }

        for (step in 0 until maxLength) {
            val decoderSeqLen = decoderInputIds.size
            val decoderInput = LongArray(decoderSeqLen) { decoderInputIds[it].toLong() }

            val nextId = bridge.runArgmaxLastStep(
                handle = session,
                int64Names = arrayOf("input_ids"),
                int64Data = arrayOf(decoderInput),
                int64Shapes = arrayOf(longArrayOf(1, decoderSeqLen.toLong())),
                logitsOutputName = "logits",
                lastStepIndex = decoderSeqLen - 1,
                vocabSize = vocabSize,
            )

            if (nextId < 0) error("ORT decoder argmax failed at step $step")
            if (nextId == tokenizer.eosTokenId) break
            generated.add(nextId)
            decoderInputIds.add(nextId)
        }

        return generated
    }

    private fun splitSentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(text) else parts
    }
}
