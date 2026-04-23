package com.example.memegram.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS port of NllbTranslationEngine. Inference is delegated to a Swift
 * bridge ([IosOnnxBridge]) wrapping `onnxruntime-objc`.
 *
 * Memory pattern matches Android: load encoder, run all sentences, close
 * encoder, then load decoder, run all sentences, close decoder. ONNX
 * sessions never overlap in memory.
 */
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

        private fun readUtf8File(path: String): String? {
            return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, error = null) as String?
        }
    }

    fun isLanguageSupported(floresCode: String): Boolean =
        tokenizer.getLangTokenId(floresCode) != null

    private var encoderSession: Long = 0L
    private var decoderSession: Long = 0L

    fun close() {
        val bridge = IosOnnxBridge.delegate ?: return
        if (encoderSession != 0L) {
            bridge.closeSession(encoderSession)
            encoderSession = 0L
        }
        if (decoderSession != 0L) {
            bridge.closeSession(decoderSession)
            decoderSession = 0L
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
    ): String = withContext(Dispatchers.Default) {
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

        encoderSession = ensureSession(bridge, encoderPath, encoderSession)
        val encoded: List<Encoded> = tokenized.map { tok ->
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

        decoderSession = ensureSession(bridge, decoderPath, decoderSession)
        val results: List<String> = tokenized.zip(encoded).map { (tok, enc) ->
            val ids = decodeAutoregressive(bridge, decoderSession, tok.decoderStartIds, enc.hidden, enc.shape, enc.mask)
            tokenizer.decode(ids)
        }

        results.joinToString(" ")
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

        for (step in 0 until maxLength) {
            val decoderSeqLen = decoderInputIds.size.toLong()
            val decoderInput = LongArray(decoderInputIds.size) { decoderInputIds[it].toLong() }

            val outputs = bridge.run(
                handle = session,
                int64Names = arrayOf("input_ids", "encoder_attention_mask"),
                int64Data = arrayOf(decoderInput, encoderAttentionMask),
                int64Shapes = arrayOf(longArrayOf(1, decoderSeqLen), longArrayOf(1, srcSeqLen)),
                floatNames = arrayOf("encoder_hidden_states"),
                floatData = arrayOf(hiddenData),
                floatShapes = arrayOf(hiddenShape),
                outputNames = arrayOf("logits"),
            )
            val logitsOut = outputs[0]
            val vocabSize = logitsOut.shape.last().toInt()
            val lastStart = (decoderSeqLen.toInt() - 1) * vocabSize

            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            val data = logitsOut.data
            for (i in 0 until vocabSize) {
                val v = data[lastStart + i]
                if (v > bestVal) { bestVal = v; bestIdx = i }
            }

            if (bestIdx == tokenizer.eosTokenId) break
            generated.add(bestIdx)
            decoderInputIds.add(bestIdx)
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
