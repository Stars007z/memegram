// JNI bridge for whisper.cpp, привязан к
// `com.example.memegram.audio.WhisperSpeechToTextService` (Memegram).
//
// Экспортирует три функции:
//   nativeInit(modelPath: String): Boolean
//   nativeTranscribe(wavPath: String, language: String): String
//   nativeRelease()
//
// WAV-парсер robust: поддерживает PCM 8/16/24/32 + IEEE float32, downmix в
// mono и линейный ресемпл в 16 kHz (whisper.cpp требует 16 kHz mono float).
// Глобальное состояние защищено std::mutex.

#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <mutex>
#include <ctime>
#include <whisper.h>
#include <android/log.h>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ── Forward declarations ──────────────────────────────────────────────
struct WavInfo {
    uint16_t num_channels    = 0;
    uint32_t sample_rate     = 0;
    uint16_t bits_per_sample = 0;
    uint16_t audio_format    = 0; // 1 = PCM, 3 = IEEE float
};

static bool load_wav_file(const char* filename, std::vector<float>& pcm_mono_16k);
static long get_current_time_ms();
static void downmix_to_mono(const std::vector<float>& in, int channels, std::vector<float>& out);
static void resample_linear(const std::vector<float>& in, int src_hz, int dst_hz,
                            std::vector<float>& out);

// ── Глобальное состояние ──────────────────────────────────────────────
static whisper_context* g_ctx = nullptr;
static std::mutex       g_ctx_mutex;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_memegram_audio_WhisperSpeechToTextService_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath) {

    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // GPU-бэкенд на Android часто нестабилен / не собран

    g_ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (g_ctx == nullptr) {
        LOGE("Failed to load model!");
        return JNI_FALSE;
    }
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_memegram_audio_WhisperSpeechToTextService_nativeTranscribe(
        JNIEnv* env, jobject /*thiz*/, jstring audioPath, jstring language) {

    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    if (g_ctx == nullptr) {
        return env->NewStringUTF("ERROR: Model not initialized");
    }

    const char* audioPathC = env->GetStringUTFChars(audioPath, nullptr);
    const char* langC      = env->GetStringUTFChars(language, nullptr);

    LOGI("Transcribing: %s (lang=%s)", audioPathC, langC ? langC : "null");

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress   = false;
    wparams.print_special    = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.translate        = false;
    wparams.n_threads        = 4;
    wparams.offset_ms        = 0;
    wparams.duration_ms      = 0;
    wparams.no_speech_thold  = 0.3f;     // чуть мягче чем default 0.6
    wparams.no_context       = true;
    wparams.single_segment   = false;

    // Real-time callback — логируем КАЖДЫЙ сегмент в момент его создания.
    wparams.new_segment_callback = [](struct whisper_context* ctx,
                                      struct whisper_state* /*state*/,
                                      int n_new, void* /*user*/) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = n - n_new; i < n; ++i) {
            const char* t = whisper_full_get_segment_text(ctx, i);
            LOGI("  [callback] seg[%d]: \"%s\"", i, t ? t : "(null)");
        }
    };
    wparams.new_segment_callback_user_data = nullptr;

    // whisper.cpp ожидает либо nullptr/"" для автодетекта, либо ISO-код.
    // Строка "auto" приведёт к whisper_lang_id() == -1 → silent fallback на EN.
    std::string langStr = (langC != nullptr) ? std::string(langC) : std::string();
    if (langStr.empty() || langStr == "auto") {
        wparams.language        = nullptr;
        wparams.detect_language = true;
    } else {
        wparams.language        = langC;
        wparams.detect_language = false;
    }

    std::vector<float> pcm_data;
    bool wav_ok = load_wav_file(audioPathC, pcm_data);

    auto cleanup_and_return = [&](const std::string& out) -> jstring {
        env->ReleaseStringUTFChars(audioPath, audioPathC);
        env->ReleaseStringUTFChars(language,  langC);
        return env->NewStringUTF(out.c_str());
    };

    if (!wav_ok)              return cleanup_and_return("ERROR: Failed to load audio file");
    if (pcm_data.empty())     return cleanup_and_return("ERROR: Empty audio data");

    // Анализ уровня сигнала + автоматический gain (на случай очень тихой записи).
    {
        float peak = 0.0f;
        double sumsq = 0.0;
        for (float s : pcm_data) {
            float a = s < 0 ? -s : s;
            if (a > peak) peak = a;
            sumsq += (double) s * (double) s;
        }
        float rms = (float) std::sqrt(sumsq / (double) pcm_data.size());
        float duration_s = (float) pcm_data.size() / 16000.0f;
        LOGI("PCM stats: samples=%zu (%.2fs) peak=%.4f rms=%.4f",
             pcm_data.size(), duration_s, peak, rms);

        if (peak > 0.0f && peak < 0.3f) {
            // Усиливаем до пика 0.9, но не больше чем в 20× (иначе шум усилим).
            float gain = 0.9f / peak;
            if (gain > 20.0f) gain = 20.0f;
            LOGI("Applying gain x%.2f (signal too quiet)", gain);
            for (float& s : pcm_data) {
                float v = s * gain;
                if (v >  1.0f) v =  1.0f;
                if (v < -1.0f) v = -1.0f;
                s = v;
            }
        } else if (peak == 0.0f) {
            LOGE("PCM is completely silent (peak=0)");
        }
    }

    long t0 = get_current_time_ms();
    int rc = whisper_full(g_ctx, wparams, pcm_data.data(), (int) pcm_data.size());
    long t1 = get_current_time_ms();

    int detected_lang_id = whisper_full_lang_id(g_ctx);
    const char* detected_lang = (detected_lang_id >= 0)
        ? whisper_lang_str(detected_lang_id) : "?";
    int n_segments = whisper_full_n_segments(g_ctx);
    LOGI("whisper_full rc=%d took=%ldms segments=%d lang=%s(id=%d)",
         rc, t1 - t0, n_segments, detected_lang, detected_lang_id);

    std::string finalText;
    if (rc == 0) {
        for (int i = 0; i < n_segments; ++i) {
            const char* seg = whisper_full_get_segment_text(g_ctx, i);
            if (seg) {
                LOGI("  seg[%d] = \"%s\"", i, seg);
                finalText += seg;
            }
            if (i < n_segments - 1) finalText += " ";
        }
        if (finalText.empty()) {
            LOGW("whisper returned 0 segments — silence or threshold rejected");
        }
    } else {
        finalText = "ERROR: Transcription failed (code: " + std::to_string(rc) + ")";
    }

    return cleanup_and_return(finalText);
}

JNIEXPORT void JNICALL
Java_com_example_memegram_audio_WhisperSpeechToTextService_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("Model released");
    }
}

} // extern "C"

// ── WAV loader ────────────────────────────────────────────────────────
static uint32_t read_u32_le(const uint8_t* p) {
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) |
           ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}
static uint16_t read_u16_le(const uint8_t* p) {
    return (uint16_t) (p[0] | (p[1] << 8));
}

static bool load_wav_file(const char* filename, std::vector<float>& pcm_mono_16k) {
    std::ifstream file(filename, std::ios::binary);
    if (!file) { LOGE("Cannot open file: %s", filename); return false; }
    std::vector<uint8_t> buf((std::istreambuf_iterator<char>(file)),
                              std::istreambuf_iterator<char>());
    if (buf.size() < 44) { LOGE("WAV too small: %zu bytes", buf.size()); return false; }
    if (std::memcmp(buf.data(), "RIFF", 4) != 0 ||
        std::memcmp(buf.data() + 8, "WAVE", 4) != 0) {
        LOGE("Not a RIFF/WAVE file"); return false;
    }

    WavInfo info;
    const uint8_t* data_ptr = nullptr;
    uint32_t data_size = 0;

    size_t pos = 12;
    while (pos + 8 <= buf.size()) {
        const uint8_t* chunk_id = buf.data() + pos;
        uint32_t chunk_size = read_u32_le(buf.data() + pos + 4);
        size_t body = pos + 8;
        if (body + chunk_size > buf.size()) {
            LOGW("Chunk overflow, truncating");
            chunk_size = (uint32_t) (buf.size() - body);
        }
        if (std::memcmp(chunk_id, "fmt ", 4) == 0) {
            if (chunk_size < 16) { LOGE("fmt chunk too small"); return false; }
            info.audio_format    = read_u16_le(buf.data() + body + 0);
            info.num_channels    = read_u16_le(buf.data() + body + 2);
            info.sample_rate     = read_u32_le(buf.data() + body + 4);
            info.bits_per_sample = read_u16_le(buf.data() + body + 14);
        } else if (std::memcmp(chunk_id, "data", 4) == 0) {
            data_ptr  = buf.data() + body;
            data_size = chunk_size;
            break;
        }
        pos = body + chunk_size + (chunk_size & 1u);
    }

    if (!data_ptr || data_size == 0 || info.sample_rate == 0 || info.num_channels == 0) {
        LOGE("WAV missing data/fmt (sr=%u ch=%u size=%u)",
             info.sample_rate, info.num_channels, data_size);
        return false;
    }
    LOGI("WAV: fmt=%u ch=%u sr=%u bps=%u data=%u",
         info.audio_format, info.num_channels, info.sample_rate,
         info.bits_per_sample, data_size);

    std::vector<float> interleaved;
    if (info.audio_format == 1) {
        if (info.bits_per_sample == 16) {
            const size_t n = data_size / 2;
            interleaved.resize(n);
            const int16_t* src = reinterpret_cast<const int16_t*>(data_ptr);
            for (size_t i = 0; i < n; ++i) interleaved[i] = src[i] / 32768.0f;
        } else if (info.bits_per_sample == 8) {
            const size_t n = data_size;
            interleaved.resize(n);
            for (size_t i = 0; i < n; ++i)
                interleaved[i] = ((int) data_ptr[i] - 128) / 128.0f;
        } else if (info.bits_per_sample == 24) {
            const size_t n = data_size / 3;
            interleaved.resize(n);
            for (size_t i = 0; i < n; ++i) {
                int32_t v = (int32_t) data_ptr[i * 3 + 0]
                          | ((int32_t) data_ptr[i * 3 + 1] << 8)
                          | ((int32_t) data_ptr[i * 3 + 2] << 16);
                if (v & 0x00800000) v |= 0xFF000000;
                interleaved[i] = (float) v / 8388608.0f;
            }
        } else if (info.bits_per_sample == 32) {
            const size_t n = data_size / 4;
            interleaved.resize(n);
            const int32_t* src = reinterpret_cast<const int32_t*>(data_ptr);
            for (size_t i = 0; i < n; ++i) interleaved[i] = src[i] / 2147483648.0f;
        } else {
            LOGE("Unsupported PCM bit depth: %u", info.bits_per_sample);
            return false;
        }
    } else if (info.audio_format == 3 && info.bits_per_sample == 32) {
        const size_t n = data_size / 4;
        interleaved.resize(n);
        std::memcpy(interleaved.data(), data_ptr, n * sizeof(float));
    } else {
        LOGE("Unsupported WAV format=%u bps=%u", info.audio_format, info.bits_per_sample);
        return false;
    }

    std::vector<float> mono;
    downmix_to_mono(interleaved, info.num_channels, mono);

    if (info.sample_rate == 16000) pcm_mono_16k = std::move(mono);
    else resample_linear(mono, (int) info.sample_rate, 16000, pcm_mono_16k);
    return true;
}

static void downmix_to_mono(const std::vector<float>& in, int channels,
                            std::vector<float>& out) {
    if (channels <= 1) { out = in; return; }
    const size_t frames = in.size() / channels;
    out.resize(frames);
    for (size_t f = 0; f < frames; ++f) {
        float sum = 0.0f;
        for (int c = 0; c < channels; ++c) sum += in[f * channels + c];
        out[f] = sum / channels;
    }
}

static void resample_linear(const std::vector<float>& in, int src_hz, int dst_hz,
                            std::vector<float>& out) {
    if (in.empty() || src_hz <= 0 || dst_hz <= 0) { out.clear(); return; }
    const double ratio = (double) dst_hz / (double) src_hz;
    const size_t out_n = (size_t) ((double) in.size() * ratio);
    out.resize(out_n);
    for (size_t i = 0; i < out_n; ++i) {
        double src_pos = (double) i / ratio;
        size_t i0 = (size_t) src_pos;
        size_t i1 = i0 + 1 < in.size() ? i0 + 1 : i0;
        double frac = src_pos - (double) i0;
        out[i] = (float) (in[i0] * (1.0 - frac) + in[i1] * frac);
    }
}

static long get_current_time_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long) (ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}
