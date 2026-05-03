package com.example.memegram.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

class NllbTranslationEngine private constructor(
    private val env: OrtEnvironment,
    private val encoderFile: File,
    private val decoderFile: File,
    private val tokenizer: NllbTokenizer,
    private val maxLength: Int,
) {
    companion object {
        private const val TAG = "NLLB"

        suspend fun load(modelDir: File): NllbTranslationEngine = withContext(Dispatchers.IO) {
            require(modelDir.isDirectory) { "Model directory does not exist: $modelDir" }

            val encoderFile = File(modelDir, "encoder_model.onnx")
            val decoderFile = File(modelDir, "decoder_model.onnx")
            val tokenizerFile = File(modelDir, "tokenizer.json")
            val configFile = File(modelDir, "config.json")

            require(encoderFile.exists()) { "encoder_model.onnx not found in $modelDir" }
            require(decoderFile.exists()) { "decoder_model.onnx not found in $modelDir" }
            require(tokenizerFile.exists()) { "tokenizer.json not found in $modelDir" }

            Log.d(TAG, "load(): encoder=${encoderFile.length() / 1024 / 1024}MB, " +
                    "decoder=${decoderFile.length() / 1024 / 1024}MB")

            val t0 = System.currentTimeMillis()
            val tokenizerJson = tokenizerFile.readText()
            val configJson = if (configFile.exists()) configFile.readText() else null
            val tokenizer = NllbTokenizer.fromJson(tokenizerJson, configJson)
            Log.d(TAG, "load(): tokenizer parsed in ${System.currentTimeMillis() - t0}ms")

            val env = OrtEnvironment.getEnvironment()

            var maxLength = 128
            if (configJson != null) {
                try {
                    val parsed = Json.parseToJsonElement(configJson).jsonObject
                    maxLength = (parsed["max_length"]?.jsonPrimitive?.int ?: 128).coerceAtMost(128)
                } catch (_: Exception) { /* use default */ }
            }

            Log.d(TAG, "load(): READY in ${System.currentTimeMillis() - t0}ms, maxLength=$maxLength " +
                    "(batch-sequential — sessions loaded on demand)")

            NllbTranslationEngine(
                env = env,
                encoderFile = encoderFile,
                decoderFile = decoderFile,
                tokenizer = tokenizer,
                maxLength = maxLength,
            )
        }

        private fun createSessionOptions() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(2))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
    }

    suspend fun translate(
        text: String,
        srcLangFlores: String,
        tgtLangFlores: String,
        onProgress: (TranslationProgress) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        val sentences = if (text.length > 300) splitSentences(text) else listOf(text)
        val totalSentences = sentences.size.coerceAtLeast(1)
        fun emitProgress(
            phase: TranslationProgressPhase,
            fraction: Float,
            completedSentences: Int = 0,
        ) {
            onProgress(
                TranslationProgress(
                    fraction = fraction.coerceIn(0f, 0.98f),
                    phase = phase,
                    completedSentences = completedSentences.coerceIn(0, totalSentences),
                    totalSentences = totalSentences,
                )
            )
        }

        Log.d(TAG, "translate(): ${sentences.size} sentence(s), total ${text.length} chars")

        // ── Step 1: Tokenize all sentences ───────────────────────────
        emitProgress(TranslationProgressPhase.TOKENIZING, 0.18f)
        data class TokenizedSentence(
            val inputIds: List<Int>,
            val decoderStartIds: List<Int>,
            val attentionMask: LongArray
        )

        val tokenized = sentences.mapIndexed { index, s ->
            val inputIds = tokenizer.encode(s, srcLangFlores)
            val decoderStartIds = tokenizer.decoderStartIds(tgtLangFlores)
            val attentionMask = LongArray(inputIds.size) { 1L }
            Log.d(TAG, "translate(): tokenized '${s.take(30)}...' → ${inputIds.size} tokens")
            emitProgress(
                TranslationProgressPhase.TOKENIZING,
                0.18f + 0.04f * ((index + 1).toFloat() / totalSentences)
            )
            TokenizedSentence(inputIds, decoderStartIds, attentionMask)
        }

        // ── Step 2: ENCODER PHASE — load once, encode all, close ─────
        data class EncodedSentence(
            val hiddenData: FloatArray,
            val hiddenShape: LongArray,
            val attentionMask: LongArray
        )

        val encoded: List<EncodedSentence> = run {
            val tLoad = System.currentTimeMillis()
            val opts = createSessionOptions()
            val encoderSession = env.createSession(encoderFile.absolutePath, opts)
            Log.d(TAG, "translate(): encoder session loaded in ${System.currentTimeMillis() - tLoad}ms")
            emitProgress(TranslationProgressPhase.ENCODING, 0.26f)

            try {
                tokenized.mapIndexed { index, tok ->
                    val seqLen = tok.inputIds.size.toLong()
                    val inputIdsArray = tok.inputIds.map { it.toLong() }.toLongArray()

                    val inputIdsTensor = OnnxTensor.createTensor(
                        env, LongBuffer.wrap(inputIdsArray), longArrayOf(1, seqLen)
                    )
                    val attMaskTensor = OnnxTensor.createTensor(
                        env, LongBuffer.wrap(tok.attentionMask), longArrayOf(1, seqLen)
                    )

                    val tEnc = System.currentTimeMillis()
                    val outputs = encoderSession.run(
                        mapOf("input_ids" to inputIdsTensor, "attention_mask" to attMaskTensor)
                    )
                    Log.d(TAG, "translate(): encoder inference ${System.currentTimeMillis() - tEnc}ms, seqLen=$seqLen")

                    val hiddenTensor = outputs[0] as OnnxTensor
                    val hiddenShape = hiddenTensor.info.shape
                    val hiddenBuf = hiddenTensor.floatBuffer
                    val hiddenData = FloatArray(hiddenBuf.remaining())
                    hiddenBuf.get(hiddenData)

                    outputs.close()
                    inputIdsTensor.close()
                    attMaskTensor.close()

                    emitProgress(
                        TranslationProgressPhase.ENCODING,
                        0.26f + 0.14f * ((index + 1).toFloat() / totalSentences)
                    )
                    EncodedSentence(hiddenData, hiddenShape, tok.attentionMask)
                }
            } finally {
                encoderSession.close()
                System.gc()
                val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                Log.d(TAG, "translate(): encoder session CLOSED, nativeHeap=${nativeHeapMB}MB")
            }
        }

        // ── Step 3: DECODER PHASE — load once, decode all, close ─────
        val results: List<String> = run {
            val tLoad = System.currentTimeMillis()
            val opts = createSessionOptions()
            emitProgress(TranslationProgressPhase.LOADING_DECODER, 0.42f)
            val decoderSession = env.createSession(decoderFile.absolutePath, opts)
            Log.d(TAG, "translate(): decoder session loaded in ${System.currentTimeMillis() - tLoad}ms")
            emitProgress(TranslationProgressPhase.DECODING, 0.50f)

            try {
                tokenized.zip(encoded).mapIndexed { index, (tok, enc) ->
                    fun sentenceProgress(tokenProgress: Float) {
                        val sentenceFraction = (index.toFloat() + tokenProgress.coerceIn(0f, 1f)) / totalSentences
                        emitProgress(
                            TranslationProgressPhase.DECODING,
                            0.50f + 0.46f * sentenceFraction,
                            completedSentences = index
                        )
                    }
                    val outputIds = decodeAutoregressive(
                        decoderSession, tok.decoderStartIds, enc.hiddenData,
                        enc.hiddenShape, enc.attentionMask,
                        onTokenProgress = ::sentenceProgress
                    )
                    emitProgress(
                        TranslationProgressPhase.DECODING,
                        0.50f + 0.46f * ((index + 1).toFloat() / totalSentences),
                        completedSentences = index + 1
                    )
                    tokenizer.decode(outputIds)
                }
            } finally {
                decoderSession.close()
                val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                Log.d(TAG, "translate(): decoder session CLOSED, nativeHeap=${nativeHeapMB}MB")
            }
        }

        emitProgress(TranslationProgressPhase.FINISHING, 0.98f, totalSentences)
        results.joinToString(" ")
    }

    private fun decodeAutoregressive(
        decoderSession: OrtSession,
        decoderStartIds: List<Int>,
        hiddenData: FloatArray,
        hiddenShape: LongArray,
        encoderAttentionMask: LongArray,
        onTokenProgress: (Float) -> Unit = {},
    ): List<Int> {
        val generatedIds = mutableListOf<Int>()
        val decoderInputIds = decoderStartIds.toMutableList()
        val seqLen = encoderAttentionMask.size.toLong()

        val hiddenStatesTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(hiddenData), hiddenShape
        )

        val tDec = System.currentTimeMillis()

        try {
            for (step in 0 until maxLength) {
                val decoderInput = decoderInputIds.map { it.toLong() }.toLongArray()
                val decoderSeqLen = decoderInputIds.size.toLong()

                val decoderInputTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(decoderInput), longArrayOf(1, decoderSeqLen)
                )
                val encoderAttMaskTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(encoderAttentionMask), longArrayOf(1, seqLen)
                )

                var decoderOutputs: OrtSession.Result? = null
                try {
                    decoderOutputs = decoderSession.run(
                        mapOf(
                            "input_ids" to decoderInputTensor,
                            "encoder_hidden_states" to hiddenStatesTensor,
                            "encoder_attention_mask" to encoderAttMaskTensor
                        )
                    )

                    val logits = extractLastStepLogits(
                        decoderOutputs[0] as OnnxTensor,
                        decoderSeqLen.toInt()
                    )

                    val nextTokenId = logits.indices.maxByOrNull { logits[it] } ?: break

                    if (nextTokenId == tokenizer.eosTokenId) {
                        Log.d(TAG, "decode(): EOS at step $step (${System.currentTimeMillis() - tDec}ms)")
                        onTokenProgress(1f)
                        break
                    }

                    generatedIds.add(nextTokenId)
                    decoderInputIds.add(nextTokenId)
                    onTokenProgress((step + 1).toFloat() / maxLength)

                    if (step % 10 == 9) {
                        val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                        Log.d(TAG, "decode(): step=$step, decoderSeqLen=${decoderInputIds.size}, nativeHeap=${nativeHeapMB}MB")
                    }
                } finally {
                    decoderInputTensor.close()
                    encoderAttMaskTensor.close()
                    decoderOutputs?.close()
                }
            }
        } finally {
            hiddenStatesTensor.close()
        }

        Log.d(TAG, "decode(): ${generatedIds.size} tokens in ${System.currentTimeMillis() - tDec}ms")
        return generatedIds
    }

    private fun splitSentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(text) else parts
    }

    private fun extractLastStepLogits(logitsTensor: OnnxTensor, decoderSeqLen: Int): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val logits3d = logitsTensor.value as Array<Array<FloatArray>>
        return logits3d[0][decoderSeqLen - 1]
    }

    fun isLanguageSupported(floresCode: String): Boolean =
        tokenizer.getLangTokenId(floresCode) != null

    fun close() {
    }
}
