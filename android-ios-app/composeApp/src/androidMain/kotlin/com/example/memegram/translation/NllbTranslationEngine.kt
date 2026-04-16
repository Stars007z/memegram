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

/**
 * ONNX Runtime inference engine for NLLB-200 translation.
 *
 * Implements the full encoder-decoder pipeline:
 *   1. Encoder: source token IDs → hidden states
 *   2. Decoder: hidden states → target token IDs (greedy, autoregressive)
 *
 * MEMORY DESIGN — BATCH-SEQUENTIAL:
 *   Encoder (399MB) and decoder (698MB) ONNX sessions are NEVER loaded
 *   simultaneously. Instead, translation is split into two phases:
 *
 *   Phase 1: Load encoder → run ALL sentences → copy hidden states → CLOSE encoder
 *   Phase 2: Load decoder → decode ALL sentences using cached hidden states → CLOSE decoder
 *
 *   This gives us the best of both worlds:
 *   - Peak native memory: max(encoder, decoder) ≈ 700MB (like per-sentence sequential)
 *   - Session load overhead: 1 encoder + 1 decoder ≈ 2.5s total (like simultaneous)
 *   - NOT 2N loads like the old per-sentence sequential approach
 *
 * NLLB-200 is a single multilingual model supporting 200 languages.
 * No English pivoting needed — translates directly between any pair.
 *
 * Input format:
 *   Encoder: [src_lang_id, text_tokens..., eos_id]
 *   Decoder: starts with [eos_id, tgt_lang_id], then generates greedily
 *
 * Models are exported by our tools/export_nllb.py script.
 */
class NllbTranslationEngine private constructor(
    private val env: OrtEnvironment,
    private val encoderFile: File,
    private val decoderFile: File,
    private val tokenizer: NllbTokenizer,
    private val maxLength: Int,
) {
    companion object {
        private const val TAG = "NLLB"

        /**
         * Prepare the NLLB engine from [modelDir].
         *
         * Lightweight — only parses tokenizer and config.
         * ONNX sessions are created per-phase in [translate] (batch-sequential).
         *
         * Expected files:
         *   - encoder_model.onnx
         *   - decoder_model.onnx
         *   - tokenizer.json
         *   - config.json (optional)
         */
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

            // Parse max_length from config.
            // Cap at 128 to limit peak memory: without KV-cache the decoder
            // re-processes ALL tokens at every step → memory grows quadratically.
            // 128 output tokens ~ 80-100 words, enough for messenger messages.
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

        /**
         * Create ONNX session options optimized for low memory.
         */
        private fun createSessionOptions() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(2))
            // BASIC_OPT: lightweight optimizations only (constant folding, redundant node
            // elimination). ALL_OPT creates temporary copies during graph transformation,
            // nearly doubling peak memory for the 698MB decoder.
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            // Disable arena allocator: the default arena pools memory and does NOT return
            // it to the OS when the session is closed. We need close() to actually free
            // native memory between encoder and decoder phases.
            setCPUArenaAllocator(false)
            // Disable memory pattern optimization to reduce peak memory during inference.
            setMemoryPatternOptimization(false)
        }
    }

    /**
     * Translate [text] from [srcLangFlores] to [tgtLangFlores].
     *
     * Uses batch-sequential approach:
     *   1. Tokenize all sentences
     *   2. Load encoder → encode ALL sentences → close encoder (~400MB freed)
     *   3. Load decoder → decode ALL sentences → close decoder
     *
     * Sessions are never in memory simultaneously.
     */
    suspend fun translate(
        text: String,
        srcLangFlores: String,
        tgtLangFlores: String
    ): String = withContext(Dispatchers.Default) {
        val sentences = if (text.length > 300) splitSentences(text) else listOf(text)
        Log.d(TAG, "translate(): ${sentences.size} sentence(s), total ${text.length} chars")

        // ── Step 1: Tokenize all sentences ───────────────────────────
        data class TokenizedSentence(
            val inputIds: List<Int>,
            val decoderStartIds: List<Int>,
            val attentionMask: LongArray
        )

        val tokenized = sentences.map { s ->
            val inputIds = tokenizer.encode(s, srcLangFlores)
            val decoderStartIds = tokenizer.decoderStartIds(tgtLangFlores)
            val attentionMask = LongArray(inputIds.size) { 1L }
            Log.d(TAG, "translate(): tokenized '${s.take(30)}...' → ${inputIds.size} tokens")
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

            try {
                tokenized.map { tok ->
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

                    // Copy hidden states to Java heap before closing session
                    val hiddenTensor = outputs[0] as OnnxTensor
                    val hiddenShape = hiddenTensor.info.shape
                    val hiddenBuf = hiddenTensor.floatBuffer
                    val hiddenData = FloatArray(hiddenBuf.remaining())
                    hiddenBuf.get(hiddenData)

                    // Close tensors (not session)
                    outputs.close()
                    inputIdsTensor.close()
                    attMaskTensor.close()

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
            val decoderSession = env.createSession(decoderFile.absolutePath, opts)
            Log.d(TAG, "translate(): decoder session loaded in ${System.currentTimeMillis() - tLoad}ms")

            try {
                tokenized.zip(encoded).map { (tok, enc) ->
                    val outputIds = decodeAutoregressive(
                        decoderSession, tok.decoderStartIds, enc.hiddenData,
                        enc.hiddenShape, enc.attentionMask
                    )
                    tokenizer.decode(outputIds)
                }
            } finally {
                decoderSession.close()
                val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                Log.d(TAG, "translate(): decoder session CLOSED, nativeHeap=${nativeHeapMB}MB")
            }
        }

        results.joinToString(" ")
    }

    /**
     * Run autoregressive greedy decoding for a single sentence.
     */
    private fun decodeAutoregressive(
        decoderSession: OrtSession,
        decoderStartIds: List<Int>,
        hiddenData: FloatArray,
        hiddenShape: LongArray,
        encoderAttentionMask: LongArray
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
                        break
                    }

                    generatedIds.add(nextTokenId)
                    decoderInputIds.add(nextTokenId)

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

    /**
     * Extract logits for the last time step.
     * Shape: [1, seq_len, vocab_size] → [vocab_size] at last position.
     */
    private fun extractLastStepLogits(logitsTensor: OnnxTensor, decoderSeqLen: Int): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val logits3d = logitsTensor.value as Array<Array<FloatArray>>
        return logits3d[0][decoderSeqLen - 1]
    }

    /**
     * Check if a FLORES language code is supported by this model.
     */
    fun isLanguageSupported(floresCode: String): Boolean =
        tokenizer.getLangTokenId(floresCode) != null

    /**
     * No-op: sessions are created/destroyed per-phase in [translate].
     * Kept for interface compatibility with NllbModelManager.
     */
    fun close() {
        // Nothing to close — no persistent ONNX sessions
    }
}
