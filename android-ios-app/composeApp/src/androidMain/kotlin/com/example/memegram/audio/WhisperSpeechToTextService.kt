package com.example.memegram.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device транскрибация через whisper.cpp (JNI).
 *
 * Pipeline:
 *  1. скачиваем ggml-модель (~470 MB) с HuggingFace в filesDir при первом вызове,
 *     с поддержкой возобновления через `.part` + Range и прогрессом через [modelDownloadState],
 *  2. декодируем сжатый войс (m4a/aac/ogg) → PCM 16 kHz mono через MediaCodec,
 *  3. пишем во временный WAV,
 *  4. дёргаем native `transcribeFile`,
 *  5. удаляем временные файлы.
 */
class WhisperSpeechToTextService(
    private val context: Context
) : SpeechToTextService {

    companion object {
        private const val TAG = "WhisperSTT"
        private const val MODEL_NAME = "ggml-base.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
        private const val MIN_MODEL_SIZE_BYTES = 100L * 1024L * 1024L
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L

        // Старая (битая) квантизированная модель — удаляем при инициализации,
        // чтобы освободить ~470 MB на устройстве.
        private const val LEGACY_MODEL_NAME = "ggml-small-q5_1.bin"

        init {
            try {
                System.loadLibrary("whisperjni")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load libwhisperjni.so: ${t.message}", t)
            }
        }
    }

    override val isSupported: Boolean = true

    private val initMutex = Mutex()
    private val inferMutex = Mutex()

    @Volatile
    private var initialized = false

    private val _modelDownloadState =
        MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    override val modelDownloadState: StateFlow<ModelDownloadState> =
        _modelDownloadState.asStateFlow()

    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60 * 60 * 1000L  // 1 час на скачивание
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    override suspend fun transcribe(
        audioBytes: ByteArray,
        mimeType: String?,
        hintLanguage: String?
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "transcribe() called: bytes=${audioBytes.size}, mime=$mimeType, hint=$hintLanguage")
        ensureInitialized()

        val ext = guessExtension(mimeType)
        val tmpEncoded = File.createTempFile("voice_", ext, context.cacheDir).apply {
            writeBytes(audioBytes)
        }
        val tmpWav = File.createTempFile("voice_", ".wav", context.cacheDir)
        try {
            Log.i(TAG, "Decoding ${tmpEncoded.name} (${tmpEncoded.length()} bytes) → WAV…")
            decodeToMonoWav16k(tmpEncoded, tmpWav)
            Log.i(TAG, "Decoded WAV: ${tmpWav.length()} bytes (header=44, data=${tmpWav.length() - 44})")
            if (tmpWav.length() <= 44L) {
                throw IOException("Decoded WAV is empty (input ${audioBytes.size}b, mime=$mimeType)")
            }

            val normLang = hintLanguage
                ?.takeIf { it.isNotBlank() && it != "auto" }
                ?: "auto"

            Log.i(TAG, "Calling nativeTranscribe(lang=$normLang)…")
            val text = inferMutex.withLock {
                nativeTranscribe(tmpWav.absolutePath, normLang)
            }
            Log.i(TAG, "nativeTranscribe returned ${text.length} chars: \"${text.take(120)}\"")
            if (text.startsWith("ERROR")) {
                throw IOException(text)
            }
            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                Log.w(TAG, "Whisper returned blank text — no speech detected (raw len=${text.length})")
            }
            TranscriptionResult(
                text = trimmed,
                language = normLang // whisper.cpp пока не возвращает detected через JNI
            )
        } finally {
            tmpEncoded.delete()
            tmpWav.delete()
        }
    }

    override fun close() {
        if (initialized) {
            runCatching { nativeRelease() }
            initialized = false
        }
        runCatching { httpClient.close() }
    }

    // ── init ─────────────────────────────────────────────────────────

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            val model = ensureModel()
            val ok = nativeInit(model.absolutePath)
            if (!ok) throw IOException("Failed to load whisper model: ${model.absolutePath}")
            initialized = true
            Log.i(TAG, "Whisper initialized (${model.length()} bytes)")
        }
    }

    /**
     * Гарантирует что модель есть в filesDir. Если нет — качает с HuggingFace
     * с поддержкой возобновления (Range request → дописываем `.part` файл).
     * Прогресс публикуется в [_modelDownloadState] не чаще раза в [PROGRESS_EMIT_INTERVAL_MS].
     */
    private suspend fun ensureModel(): File {
        // Удаляем старую битую q5_1 модель, если осталась с предыдущей версии.
        val legacy = File(context.filesDir, LEGACY_MODEL_NAME)
        if (legacy.exists()) {
            val freed = legacy.length()
            if (legacy.delete()) {
                Log.i(TAG, "Removed legacy model ${legacy.name} (freed $freed bytes)")
            }
        }
        val legacyPart = File(context.filesDir, "$LEGACY_MODEL_NAME.part")
        if (legacyPart.exists()) legacyPart.delete()

        val out = File(context.filesDir, MODEL_NAME)
        if (out.exists() && out.length() >= MIN_MODEL_SIZE_BYTES) {
            _modelDownloadState.value = ModelDownloadState.Ready
            return out
        }
        if (out.exists()) {
            Log.w(TAG, "Stale model (${out.length()} bytes) removed")
            out.delete()
        }
        val tmp = File(context.filesDir, "$MODEL_NAME.part")
        try {
            downloadModel(tmp)
            if (tmp.length() < MIN_MODEL_SIZE_BYTES) {
                throw IOException("Downloaded model too small: ${tmp.length()}")
            }
            if (!tmp.renameTo(out)) {
                // Возможно partial-rename — попробуем copy+delete
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            _modelDownloadState.value = ModelDownloadState.Ready
            return out
        } catch (e: Exception) {
            _modelDownloadState.value = ModelDownloadState.Failed(
                e.message ?: e::class.java.simpleName
            )
            throw e
        }
    }

    private suspend fun downloadModel(target: File) {
        val resumeFrom = if (target.exists()) target.length() else 0L
        Log.i(TAG, "Downloading whisper model from $MODEL_URL (resume=$resumeFrom)")

        httpClient.prepareGet(MODEL_URL) {
            if (resumeFrom > 0) {
                headers.append(HttpHeaders.Range, "bytes=$resumeFrom-")
            }
        }.execute { response ->
            // Content-Length для текущего сегмента; полный размер = resumeFrom + len.
            val segmentLen = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
            val totalBytes = if (segmentLen > 0) resumeFrom + segmentLen else -1L

            _modelDownloadState.value = ModelDownloadState.Downloading(
                bytesDownloaded = resumeFrom,
                totalBytes = totalBytes
            )

            val channel = response.bodyAsChannel()
            // Если сервер вернул 200 (а не 206) — он не поддержал Range, начинаем с нуля.
            val statusCode = response.status.value
            val appendMode = statusCode == 206 && resumeFrom > 0
            if (!appendMode && target.exists()) {
                target.delete()
            }
            var written: Long = if (appendMode) resumeFrom else 0L
            var lastEmitMs = 0L

            RandomAccessFile(target, "rw").use { raf ->
                if (appendMode) raf.seek(resumeFrom) else raf.setLength(0L)

                val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buf, 0, buf.size)
                    if (read <= 0) {
                        if (read == -1) break
                        continue
                    }
                    raf.write(buf, 0, read)
                    written += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                        lastEmitMs = now
                        _modelDownloadState.value = ModelDownloadState.Downloading(
                            bytesDownloaded = written,
                            totalBytes = totalBytes
                        )
                    }
                }
            }
            // финальный emit
            _modelDownloadState.value = ModelDownloadState.Downloading(
                bytesDownloaded = written,
                totalBytes = if (totalBytes > 0) totalBytes else written
            )
            Log.i(TAG, "Model downloaded: $written bytes")
        }
    }

    private fun guessExtension(mime: String?): String = when {
        mime == null -> ".m4a"
        mime.contains("mp4") || mime.contains("aac") -> ".m4a"
        mime.contains("ogg") || mime.contains("opus") -> ".ogg"
        mime.contains("wav") || mime.contains("x-wav") -> ".wav"
        mime.contains("mpeg") -> ".mp3"
        mime.contains("webm") -> ".webm"
        mime.contains("flac") -> ".flac"
        else -> ".m4a"
    }

    // ── decode: compressed → PCM 16 kHz mono, save as WAV ────────────

    private fun decodeToMonoWav16k(input: File, outputWav: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)

        var trackIdx = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIdx = i
                inputFormat = fmt
                break
            }
        }
        if (trackIdx < 0 || inputFormat == null) {
            extractor.release()
            throw IOException("No audio track in file")
        }
        extractor.selectTrack(trackIdx)

        val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        Log.i(TAG, "Audio track: mime=$mime, sr=$srcSampleRate, ch=$srcChannels")

        // Если это уже linear PCM — мы можем пропустить decoder.
        val pcmShort: ShortArray = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
            readRawPcm(extractor)
        } else {
            decodeCompressed(extractor, inputFormat)
        }
        extractor.release()
        Log.i(TAG, "Decoded PCM samples: ${pcmShort.size} (interleaved, $srcChannels ch)")

        // downmix → mono
        val mono: ShortArray = if (srcChannels <= 1) pcmShort
        else downmixToMono(pcmShort, srcChannels)

        // resample → 16 kHz
        val resampled: ShortArray = if (srcSampleRate == TARGET_SAMPLE_RATE) mono
        else linearResample(mono, srcSampleRate, TARGET_SAMPLE_RATE)
        Log.i(TAG, "After downmix+resample: ${resampled.size} samples @ ${TARGET_SAMPLE_RATE}Hz mono (~${resampled.size / TARGET_SAMPLE_RATE}s)")

        writeWav16kMono(outputWav, resampled)
    }

    private fun readRawPcm(extractor: MediaExtractor): ShortArray {
        val buf = ByteBuffer.allocate(256 * 1024)
        val out = ByteArray(16 * 1024 * 1024).let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        var total = 0
        while (true) {
            buf.clear()
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) break
            val limit = minOf(n, out.remaining())
            val bytes = ByteArray(limit)
            buf.get(bytes, 0, limit)
            out.put(bytes)
            total += limit
            extractor.advance()
        }
        val shorts = ShortArray(total / 2)
        val ro = ByteBuffer.wrap(out.array(), 0, total).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = ro.short
        return shorts
    }

    private fun decodeCompressed(extractor: MediaExtractor, inputFormat: MediaFormat): ShortArray {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val outPcm = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10_000L

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(ib, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            val chunk = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(chunk, 0, info.size)
                            outPcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (sawInputEOS) sawOutputEOS = true
                    }
                    // INFO_OUTPUT_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED — игнорируем
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        val bytes = outPcm.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    private fun linearResample(input: ShortArray, srcHz: Int, dstHz: Int): ShortArray {
        if (input.isEmpty() || srcHz <= 0 || dstHz <= 0) return ShortArray(0)
        val ratio = dstHz.toDouble() / srcHz.toDouble()
        val outLen = (input.size * ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt()
            val i1 = if (i0 + 1 < input.size) i0 + 1 else i0
            val frac = srcPos - i0
            val v = input[i0] * (1.0 - frac) + input[i1] * frac
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun writeWav16kMono(file: File, samples: ShortArray) {
        val byteRate = TARGET_SAMPLE_RATE * 2 // mono, 16-bit
        val dataSize = samples.size * 2
        FileOutputStream(file).use { raw ->
            DataOutputStream(raw).use { dos ->
                dos.writeBytes("RIFF")
                dos.writeIntLE(36 + dataSize)
                dos.writeBytes("WAVE")
                dos.writeBytes("fmt ")
                dos.writeIntLE(16)              // Subchunk1Size (PCM)
                dos.writeShortLE(1)             // AudioFormat = PCM
                dos.writeShortLE(1)             // NumChannels = 1
                dos.writeIntLE(TARGET_SAMPLE_RATE)
                dos.writeIntLE(byteRate)
                dos.writeShortLE(2)             // BlockAlign = 2
                dos.writeShortLE(16)            // BitsPerSample
                dos.writeBytes("data")
                dos.writeIntLE(dataSize)
                val bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
                for (s in samples) bb.putShort(s)
                dos.write(bb.array())
            }
        }
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        writeByte(v and 0xFF)
        writeByte((v ushr 8) and 0xFF)
        writeByte((v ushr 16) and 0xFF)
        writeByte((v ushr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        writeByte(v and 0xFF)
        writeByte((v ushr 8) and 0xFF)
    }

    // ── native (из ml/voice-translator/kotlin/whisperjni.cpp) ────────

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeTranscribe(wavPath: String, language: String): String
    private external fun nativeRelease()
}

/**
 * JNI-функции реализованы в `composeApp/src/androidMain/cpp/whisperjni.cpp`
 * под именами `Java_com_example_memegram_audio_WhisperSpeechToTextService_*`.
 * Сборка нативной либы libwhisperjni.so выполняется CMake-таргетом из того же каталога.
 */
