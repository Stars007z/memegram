package com.example.memegram.translation

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * Android actual implementation of [TranslationManager].
 *
 * Required assets (bundled inside the APK under src/androidMain/assets/):
 *
 *   translation/
 *     encoder_model.onnx       – MarianMT / Helsinki-NLP opus-mt-en-ru encoder
 *     decoder_model.onnx       – Matching decoder
 *     vocab.json               – HuggingFace shared vocabulary { token: id, … }  (62 518 entries)
 *     source_spm_vocab.tsv     – SentencePiece Unigram model exported as TSV
 *
 * Dependency in build.gradle.kts (androidMain):
 *   implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
 */
actual class TranslationManager(private val context: Context) {

    // ── ONNX Runtime environment (singleton per JVM) ──────────────────────────
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // ── Translation session state ─────────────────────────────────────────────
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    // ── Whisper (lazily initialised, reused across calls) ─────────────────────
    private var whisperTranscriber: WhisperTranscriber? = null

    // Shared vocabulary:  token string → model ID  (from vocab.json, ~62 518 entries)
    private var vocab: Map<String, Int> = emptyMap()
    // Reverse: model ID → token string  (for decoding output)
    private var reverseVocab: Map<Int, String> = emptyMap()

    // SentencePiece Unigram pieces: piece string → log-probability score
    private var spmPieces: Map<String, Float> = emptyMap()

    // Special token IDs – set during init
    private var eosId: Long = 0L      // </s> = 0 in MarianMT
    private var padId: Long = 62517L  // <pad> = 62517 — also used as decoder_start_token_id!

    private var isInitialized = false

    // Lazy initialisation: model loading is deferred to the first call to translate()
    // to avoid blocking the main thread and causing ANR / OOM during DI creation.

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    private fun initializeTranslation() {
        val options = OrtSession.SessionOptions().apply {
            addCPU(true)
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
        }

        // Copy asset to cache file to avoid loading ~200+ MB into heap
        val encoderFile = copyAssetToCache("translation/encoder_model.onnx", "translation_encoder.onnx")
        encoderSession = env.createSession(encoderFile.absolutePath, options)

        val decoderFile = copyAssetToCache("translation/decoder_model.onnx", "translation_decoder.onnx")
        decoderSession = env.createSession(decoderFile.absolutePath, options)

        // ── Shared vocabulary (token → id) ──
        val vocabJson = context.assets.open("translation/vocab.json").bufferedReader().readText()
        val jsonObj = JSONObject(vocabJson)
        vocab = buildMap(jsonObj.length()) {
            jsonObj.keys().forEach { key -> put(key, jsonObj.getInt(key)) }
        }
        reverseVocab = vocab.entries.associate { (k, v) -> v to k }

        // Read special token IDs from vocab.json
        eosId = (vocab["</s>"] ?: 0).toLong()
        padId = (vocab["<pad>"] ?: 62517).toLong()

        // ── SentencePiece Unigram model (for input tokenisation) ──
        val tsvText = context.assets.open("translation/source_spm_vocab.tsv")
            .bufferedReader().readText()
        spmPieces = buildMap {
            for (line in tsvText.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val piece = parts[0]
                val score = parts[1].toFloatOrNull() ?: continue
                val type = parts[2].toIntOrNull() ?: continue
                // type 1 = NORMAL, 2 = UNKNOWN, 3 = CONTROL
                if (type == 1) {
                    put(piece, score)
                }
            }
        }

        isInitialized = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    actual suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.Default) {
        // Lazy init: load models on first translation request (off main thread)
        if (!isInitialized) {
            try {
                initializeTranslation()
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext "⚠️ ${e.message}"
            }
        }
        if (encoderSession == null || decoderSession == null) {
            return@withContext "⚠️ Translation model not loaded"
        }
        if (text.isBlank()) return@withContext text

        try {
            runTranslation(text)
        } catch (e: Exception) {
            e.printStackTrace()
            "⚠️ ${e.message}"
        }
    }

    actual suspend fun transcribeAudio(audioBytes: ByteArray): String =
        withContext(Dispatchers.Default) {
            try {
                if (whisperTranscriber == null) {
                    whisperTranscriber = WhisperTranscriber(context)
                }
                whisperTranscriber!!.transcribe(audioBytes)
            } catch (e: Exception) {
                e.printStackTrace()
                "⚠️ ${e.message}"
            }
        }

    actual fun isTranslationAvailable(): Boolean = isInitialized || runCatching {
        context.assets.list("translation")?.isNotEmpty() == true
    }.getOrDefault(false)

    actual fun isTranscriptionAvailable(): Boolean = runCatching {
        context.assets.list("whisper")?.isNotEmpty() == true
    }.getOrDefault(false)

    actual fun release() {
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        whisperTranscriber?.release()
        whisperTranscriber = null
        isInitialized = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Translation internals
    // ─────────────────────────────────────────────────────────────────────────

    /** Full encoder-decoder greedy translation. */
    private fun runTranslation(text: String): String {
        val inputIds = tokenize(text)
        val attMask = LongArray(inputIds.size) { 1L }
        val seqLen = inputIds.size.toLong()
        val shape = longArrayOf(1L, seqLen)

        val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape)
        val attMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask), shape)

        // Encoder pass
        val encoderInputs: Map<String, OnnxTensorLike> = mapOf(
            "input_ids"      to inputTensor,
            "attention_mask"  to attMaskTensor
        )
        val encoderResult = encoderSession!!.run(encoderInputs)
        val encoderHidden: OnnxTensorLike = encoderResult.first().value as OnnxTensorLike

        // ── Greedy decoder loop ──
        // CRITICAL: MarianMT decoder_start_token_id = <pad> (62517), NOT </s> (0)!
        val maxLen = (inputIds.size * 2 + 10).coerceAtMost(256)
        val outputIds = mutableListOf(padId)  // start with <pad> = 62517

        for (step in 0 until maxLen) {
            val decLen = outputIds.size.toLong()
            val decShape = longArrayOf(1L, decLen)
            val decTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(outputIds.toLongArray()), decShape
            )

            val decoderInputs: Map<String, OnnxTensorLike> = mapOf(
                "input_ids"              to decTensor,
                "encoder_hidden_states"  to encoderHidden,
                "encoder_attention_mask"  to attMaskTensor
            )
            val decoderResult = decoderSession!!.run(decoderInputs)

            // logits: [1, dec_seq_len, vocab_size]
            val logitsEntry = decoderResult.first()
            val nextId = argmaxLastStep(logitsEntry.value.value, outputIds.size - 1)

            decoderResult.close()
            decTensor.close()

            outputIds.add(nextId)
            if (nextId == eosId) break  // </s> = 0 means end
        }

        encoderResult.close()
        inputTensor.close()
        attMaskTensor.close()

        // Strip the initial <pad> start token and trailing </s>
        val trimmed = outputIds.drop(1).dropLastWhile { it == eosId }
        return detokenize(trimmed)
    }

    // ── SentencePiece Unigram Tokenisation (Viterbi) ──────────────────────────

    /**
     * Tokenises input text using the SentencePiece Unigram (Viterbi) algorithm,
     * then maps subword pieces to model IDs via vocab.json.
     *
     * Steps:
     *   1. Normalise: prepend ▁, replace spaces with ▁
     *   2. Viterbi segmentation using piece scores from source_spm_vocab.tsv
     *   3. Map each piece to an ID through vocab.json
     *   4. Append </s> (EOS)
     */
    private fun tokenize(text: String): LongArray {
        val unkId = vocab["<unk>"]?.toLong() ?: 1L

        // SentencePiece normalisation: space → ▁
        val normalized = "\u2581" + text.replace(' ', '\u2581')
        val pieces = viterbiSegment(normalized)

        val result = LongArray(pieces.size + 1)
        for (i in pieces.indices) {
            result[i] = vocab[pieces[i]]?.toLong() ?: unkId
        }
        result[pieces.size] = eosId  // </s>
        return result
    }

    /**
     * Viterbi best-path segmentation for SentencePiece Unigram model.
     */
    private fun viterbiSegment(text: String): List<String> {
        val n = text.length
        if (n == 0) return emptyList()

        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        bestScore[0] = 0f
        val bestEdge = IntArray(n + 1)
        val maxPieceLen = 32

        for (end in 1..n) {
            for (start in maxOf(0, end - maxPieceLen) until end) {
                val piece = text.substring(start, end)
                val score = spmPieces[piece] ?: continue
                val candidate = bestScore[start] + score
                if (candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    bestEdge[end] = start
                }
            }
            // Fallback: if no piece matched, consume a single character as <unk>
            if (bestScore[end] == Float.NEGATIVE_INFINITY) {
                bestScore[end] = bestScore[end - 1] + UNK_PENALTY
                bestEdge[end] = end - 1
            }
        }

        val tokens = mutableListOf<String>()
        var pos = n
        while (pos > 0) {
            val start = bestEdge[pos]
            tokens.add(text.substring(start, pos))
            pos = start
        }
        tokens.reverse()
        return tokens
    }

    /** Convert a list of token ids back to a human-readable string. */
    private fun detokenize(ids: List<Long>): String {
        return ids
            .mapNotNull { reverseVocab[it.toInt()] }
            .joinToString("")
            .replace("\u2581", " ")  // ▁ → space
            .trim()
    }

    // ── Argmax helper ─────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun argmaxLastStep(logits: Any, seqIdx: Int): Long {
        return when (logits) {
            is Array<*> -> {
                val batch = logits as Array<Array<FloatArray>>
                val row = batch[0][seqIdx]
                var best = 0
                var bestVal = Float.NEGATIVE_INFINITY
                for (i in row.indices) {
                    if (row[i] > bestVal) {
                        bestVal = row[i]
                        best = i
                    }
                }
                best.toLong()
            }
            else -> throw IllegalStateException("Unexpected logit type: ${logits::class.java}")
        }
    }

    /**
     * Copies an asset file to the app's cache directory (if not already cached)
     * so ONNX Runtime can memory-map it instead of loading the entire file into heap.
     */
    private fun copyAssetToCache(assetPath: String, cacheFileName: String): File {
        val cacheFile = File(context.cacheDir, cacheFileName)
        if (!cacheFile.exists()) {
            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
        }
        return cacheFile
    }

    companion object {
        /** Log-probability penalty for unknown single-character fallback. */
        private const val UNK_PENALTY = -20.0f
    }
}
