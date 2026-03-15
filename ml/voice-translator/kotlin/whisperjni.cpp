#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <whisper.h>
#include <android/log.h>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

static whisper_context* g_ctx = nullptr;

JNIEXPORT jboolean JNICALL
Java_com_example_voicetranslator_WhisperLocal_initModel(
        JNIEnv* env,
        jobject thiz,
        jstring modelPath) {
    
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", path);
    
    struct whisper_context_params params = whisper_context_default_params();
    g_ctx = whisper_init_from_file(path, params);
    
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (g_ctx == nullptr) {
        LOGE("Failed to load model!");
        return JNI_FALSE;
    }
    
    LOGI("Model loaded successfully!");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_voicetranslator_WhisperLocal_transcribeFile(
        JNIEnv* env,
        jobject thiz,
        jstring audioPath,
        jstring language) {
    
    if (g_ctx == nullptr) {
        return env->NewStringUTF("ERROR: Model not initialized");
    }
    
    const char* audioPathC = env->GetStringUTFChars(audioPath, nullptr);
    const char* langC = env->GetStringUTFChars(language, nullptr);
    
    LOGI("Transcribing: %s", audioPathC);
    
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.language = langC;
    wparams.n_threads = 4;
    wparams.offset_ms = 0;
    wparams.duration_ms = 0;
    
    std::vector<float> pcm_data;
    if (!load_wav_file(audioPathC, pcm_data)) {
        env->ReleaseStringUTFChars(audioPath, audioPathC);
        env->ReleaseStringUTFChars(language, langC);
        return env->NewStringUTF("ERROR: Failed to load audio file");
    }
    
    long start_time = get_current_time_ms();
    int result = whisper_full(g_ctx, wparams, pcm_data.data(), pcm_data.size());
    long end_time = get_current_time_ms();
    
    LOGI("Transcription took: %ld ms", end_time - start_time);
    
    std::string finalText = "";
    if (result == 0) {
        int n_segments = whisper_full_n_segments(g_ctx);
        for (int i = 0; i < n_segments; i++) {
            const char* text = whisper_full_get_segment_text(g_ctx, i);
            finalText += text;
            if (i < n_segments - 1) finalText += " ";
        }
    } else {
        finalText = "ERROR: Transcription failed (code: " + std::to_string(result) + ")";
    }
    
    env->ReleaseStringUTFChars(audioPath, audioPathC);
    env->ReleaseStringUTFChars(language, langC);
    
    return env->NewStringUTF(finalText.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_voicetranslator_WhisperLocal_releaseModel(
        JNIEnv* env,
        jobject thiz) {
    
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("Model released");
    }
}

bool load_wav_file(const char* filename, std::vector<float>& pcm_data) {
    std::ifstream file(filename, std::ios::binary);
    if (!file.is_open()) {
        LOGE("Cannot open file: %s", filename);
        return false;
    }
    

    char header[44];
    file.read(header, 44);
    
    std::vector<short> samples((std::istreambuf_iterator<char>(file)),
                                std::istreambuf_iterator<char>());
    
    pcm_data.resize(samples.size());
    for (size_t i = 0; i < samples.size(); i++) {
        pcm_data[i] = samples[i] / 32768.0f;
    }
    
    return true;
}

long get_current_time_ms() {
    struct timespec res;
    clock_gettime(CLOCK_MONOTONIC, &res);
    return (res.tv_sec * 1000) + (res.tv_nsec / 1000000);
}

}
