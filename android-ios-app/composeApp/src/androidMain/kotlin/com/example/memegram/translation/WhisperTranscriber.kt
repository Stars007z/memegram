package com.example.memegram.translation

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * On-device voice transcription using Whisper (tiny) ONNX models.
 *
 * Required assets (src/androidMain/assets/whisper/):
 *   encoder_model.onnx  – Whisper encoder (input: input_features [1, 80, 3000])
 *   decoder_model.onnx  – Whisper decoder (inputs: input_ids, encoder_hidden_states)
 *   vocab.json           – Whisper tokenizer vocab {token: id}, from HuggingFace
 *
 * Sessions are lazily initialised and reused across calls. Call [release] to free them.
 */
internal class WhisperTranscriber(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MAX_AUDIO_SECONDS = 30
        private const val N_MELS = 80
        private const val HOP_LENGTH = 160
        private const val N_FFT = 400
        private const val N_FRAMES = MAX_AUDIO_SECONDS * SAMPLE_RATE / HOP_LENGTH  // 3 000
        private const val MAX_DECODE_TOKENS = 224

        // Special Whisper token ids (tiny multi-lingual checkpoint)
        private const val TOKEN_SOT           = 50258L  // <|startoftranscript|>
        private const val TOKEN_ENGLISH       = 50259L  // <|en|>
        private const val TOKEN_TRANSCRIBE    = 50359L  // <|transcribe|>
        private const val TOKEN_NO_TIMESTAMPS = 50363L  // <|notimestamps|>
        private const val TOKEN_EOT           = 50257L  // <|endoftext|>
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // Lazily initialised, reused across multiple transcribe() calls
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var isInitialized = false

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation (called once, lazily)
    // ─────────────────────────────────────────────────────────────────────────

    private fun initialize() {
        val options = OrtSession.SessionOptions().apply {
            addCPU(true)
            setIntraOpNumThreads(4)
        }
        val encoderFile = copyAssetToCache("whisper/encoder_model.onnx", "whisper_encoder.onnx")
        val decoderFile = copyAssetToCache("whisper/decoder_model.onnx", "whisper_decoder.onnx")
        encoderSession = env.createSession(encoderFile.absolutePath, options)
        decoderSession = env.createSession(decoderFile.absolutePath, options)

        vocab = loadVocab()
        isInitialized = true
    }

    /** Load Whisper vocab from vocab.json. Returns list where index = token id. */
    private fun loadVocab(): List<String> {
        val jsonText = context.assets.open("whisper/vocab.json").bufferedReader().readText()
        val jsonObj = JSONObject(jsonText)
        val map = mutableMapOf<Int, String>()
        jsonObj.keys().forEach { key ->
            map[jsonObj.getInt(key)] = key
        }
        val maxId = map.keys.maxOrNull() ?: 0
        return List(maxId + 1) { id -> map[id] ?: "" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun transcribe(audioBytes: ByteArray): String = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            initialize()
        }

        // 1. Audio → PCM float array at 16 kHz mono
        val pcm = decodeAudioToPcm(audioBytes)

        // 2. PCM → log-mel spectrogram [N_MELS × N_FRAMES]
        val mel = computeLogMelSpectrogram(pcm)

        // 3. Run Whisper
        runWhisper(mel)
    }

    fun release() {
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        isInitialized = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Whisper encoder-decoder
    // ─────────────────────────────────────────────────────────────────────────

    private fun runWhisper(mel: FloatArray): String {
        val enc = encoderSession ?: throw IllegalStateException("Encoder not initialised")
        val dec = decoderSession ?: throw IllegalStateException("Decoder not initialised")

        // Encoder: input_features [1, N_MELS, N_FRAMES]
        val melShape = longArrayOf(1L, N_MELS.toLong(), N_FRAMES.toLong())
        val melTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), melShape)

        val encoderInputs: Map<String, OnnxTensorLike> = mapOf("input_features" to melTensor)
        val encoderResult = enc.run(encoderInputs)
        val encoderHidden: OnnxTensorLike = encoderResult.first().value as OnnxTensorLike

        // Decoder: greedy search
        // Prompt: <|startoftranscript|> <|en|> <|transcribe|> <|notimestamps|>
        val prompt = longArrayOf(TOKEN_SOT, TOKEN_ENGLISH, TOKEN_TRANSCRIBE, TOKEN_NO_TIMESTAMPS)
        val outputTokens = prompt.toMutableList()

        for (step in 0 until MAX_DECODE_TOKENS) {
            val decLen = outputTokens.size.toLong()
            val decShape = longArrayOf(1L, decLen)
            val decTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(outputTokens.toLongArray()), decShape
            )

            val decoderInputs: Map<String, OnnxTensorLike> = mapOf(
                "input_ids"             to decTensor,
                "encoder_hidden_states" to encoderHidden
            )
            val decoderResult = dec.run(decoderInputs)

            // Get logits — first output of Whisper decoder
            val logitsRaw = decoderResult.first().value.value
            val nextId = argmaxLastStep(logitsRaw, outputTokens.size - 1)

            decoderResult.close()
            decTensor.close()

            if (nextId == TOKEN_EOT) break
            outputTokens.add(nextId)
        }

        encoderResult.close()
        melTensor.close()

        // Decode: drop the prompt, convert ids to text
        val textTokenIds = outputTokens.drop(prompt.size)
        return decodeTokens(textTokenIds)
            .ifBlank { "…" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio decoding via MediaCodec
    // ─────────────────────────────────────────────────────────────────────────

    private fun decodeAudioToPcm(audioBytes: ByteArray): FloatArray {
        val targetLen = SAMPLE_RATE * MAX_AUDIO_SECONDS
        val result = FloatArray(targetLen)

        val tempFile = File(context.cacheDir, "whisper_audio_${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)

            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return result

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            val srcSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else SAMPLE_RATE
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val rawSamples = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)!!
                    val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (shortBuf.hasRemaining()) {
                        rawSamples.add(shortBuf.get().toFloat() / Short.MAX_VALUE.toFloat())
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            // Downmix to mono
            val mono: List<Float> = if (srcChannels > 1) {
                rawSamples.chunked(srcChannels) { chunk -> chunk.sum() / chunk.size }
            } else rawSamples

            // Resample to 16 kHz
            val resampled: List<Float> = if (srcSampleRate != SAMPLE_RATE) {
                val ratio = srcSampleRate.toDouble() / SAMPLE_RATE
                val outLen = min((mono.size / ratio).toInt(), targetLen)
                List(outLen) { i ->
                    val srcIdx = i * ratio
                    val lo = srcIdx.toInt()
                    val hi = lo + 1
                    val frac = (srcIdx - lo).toFloat()
                    val a = mono.getOrElse(lo) { 0f }
                    val b = mono.getOrElse(hi) { 0f }
                    a + frac * (b - a)
                }
            } else mono

            val copyLen = min(resampled.size, targetLen)
            for (i in 0 until copyLen) result[i] = resampled[i]

        } finally {
            tempFile.delete()
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log-Mel spectrogram (Whisper-compatible)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes Whisper-compatible log-mel spectrogram.
     * Returns FloatArray of shape [N_MELS × N_FRAMES] in row-major order.
     *
     * Matches HuggingFace WhisperFeatureExtractor:
     *   - Hann-windowed STFT (n_fft=400, hop=160)
     *   - 80-band HTK mel filterbank with Slaney normalisation
     *   - log10(max(mel, 1e-10)), clamp to max-8, then (val+4)/4
     */
    private fun computeLogMelSpectrogram(audio: FloatArray): FloatArray {
        val hann = FloatArray(N_FFT) { n ->
            (0.5 * (1.0 - cos(2.0 * PI * n / N_FFT))).toFloat()
        }
        val numBins = N_FFT / 2 + 1  // 201

        // HTK Mel filterbank
        val fMax = SAMPLE_RATE / 2.0
        val melMin = hzToMelHTK(0.0)
        val melMax = hzToMelHTK(fMax)

        val melCenters = DoubleArray(N_MELS + 2) { i ->
            melToHzHTK(melMin + i.toDouble() * (melMax - melMin) / (N_MELS + 1))
        }
        val fftFreqs = DoubleArray(numBins) { i -> i.toDouble() * SAMPLE_RATE / N_FFT }

        // Slaney-normalised triangular filters
        val filterbank = Array(N_MELS) { m ->
            val fLow = melCenters[m]
            val fCenter = melCenters[m + 1]
            val fHigh = melCenters[m + 2]
            val enorm = 2.0 / (fHigh - fLow)
            FloatArray(numBins) { b ->
                val f = fftFreqs[b]
                val w = when {
                    f < fLow || f > fHigh -> 0.0
                    f <= fCenter -> (f - fLow) / (fCenter - fLow)
                    else -> (fHigh - f) / (fHigh - fCenter)
                }
                (w * enorm).toFloat()
            }
        }

        // STFT → power → mel → log10
        val logSpec = FloatArray(N_MELS * N_FRAMES)

        for (frame in 0 until N_FRAMES) {
            val start = frame * HOP_LENGTH
            val windowed = FloatArray(N_FFT) { k ->
                val idx = start + k
                if (idx < audio.size) audio[idx] * hann[k] else 0f
            }

            // DFT (bins 0..N_FFT/2)
            val power = FloatArray(numBins)
            for (k in 0 until numBins) {
                var re = 0.0
                var im = 0.0
                val baseAngle = -2.0 * PI * k / N_FFT
                for (n in 0 until N_FFT) {
                    val a = baseAngle * n
                    re += windowed[n] * cos(a)
                    im += windowed[n] * sin(a)
                }
                power[k] = (re * re + im * im).toFloat()
            }

            // Mel filterbank + log10
            for (m in 0 until N_MELS) {
                var energy = 0.0
                for (b in 0 until numBins) {
                    energy += (power[b] * filterbank[m][b]).toDouble()
                }
                logSpec[m * N_FRAMES + frame] = log10(max(energy, 1e-10)).toFloat()
            }
        }

        // Whisper normalisation:
        // 1. Clamp: max(val, globalMax - 8.0)
        var maxVal = -1e10f
        for (v in logSpec) if (v > maxVal) maxVal = v
        val clampFloor = maxVal - 8.0f
        for (i in logSpec.indices) {
            logSpec[i] = max(logSpec[i], clampFloor)
        }
        // 2. Normalise: (val + 4.0) / 4.0
        for (i in logSpec.indices) {
            logSpec[i] = (logSpec[i] + 4.0f) / 4.0f
        }

        return logSpec
    }

    private fun hzToMelHTK(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHzHTK(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    // ─────────────────────────────────────────────────────────────────────────
    // Token decoding
    // ─────────────────────────────────────────────────────────────────────────

    private fun decodeTokens(ids: List<Long>): String {
        return ids
            .mapNotNull { id ->
                val idx = id.toInt()
                vocab.getOrNull(idx)
            }
            .filter { !it.startsWith("<|") }   // skip special tokens
            .joinToString("")
            .replace("Ġ", " ")    // GPT-2 BPE word boundary → space
            .replace("Ċ", "\n")
            .trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Argmax helper
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun argmaxLastStep(logits: Any, seqIdx: Int): Long {
        return when (logits) {
            is Array<*> -> {
                val arr = logits as Array<Array<FloatArray>>
                val row = arr[0][seqIdx]
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
     * Copies an asset to the app's cache directory (once),
     * so ONNX Runtime can memory-map it instead of loading into heap.
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
}
