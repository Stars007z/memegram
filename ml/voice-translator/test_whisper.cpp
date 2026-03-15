#include "whisper.h"
#include <stdio.h>
#include <vector>
#include <string>

bool load_wav(const char* filename, std::vector<float>& pcm) {
    FILE* file = fopen(filename, "rb");
    if (!file) {
        fprintf(stderr, "❌ Не удалось открыть файл: %s\n", filename);
        return false;
    }
    
    char header[44];
    fread(header, 1, 44, file);
    
    std::vector<short> samples;
    short sample;
    while (fread(&sample, 2, 1, file) == 1) {
        samples.push_back(sample);
    }
    fclose(file);
    
    pcm.resize(samples.size());
    for (size_t i = 0; i < samples.size(); i++) {
        pcm[i] = samples[i] / 32768.0f;
    }
    
    return true;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        printf("Использование: %s <путь_к_модели> <путь_к_аудио>\n", argv[0]);
        return 1;
    }
    
    const char* model_path = argv[1];
    const char* audio_path = argv[2];
    
    printf("🔄 Загрузка модели: %s\n", model_path);
    
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context* ctx = whisper_init_from_file(model_path, cparams);
    
    if (!ctx) {
        fprintf(stderr, "Ошибка загрузки модели!\n");
        return 1;
    }
    printf("Модель загружена\n");
    
    std::vector<float> pcm;
    if (!load_wav(audio_path, pcm)) {
        whisper_free(ctx);
        return 1;
    }
    printf("🎤 Аудио загружено (%.2f сек)\n", (float)pcm.size() / 16000.0f);
    
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = true;
    wparams.print_special = false;
    wparams.print_timestamps = false;
    wparams.language = "ru";  // Или "en", или "auto"
    wparams.n_threads = 4;
    
    printf("🚀 Начало транскрибации...\n");
    int64_t t_start = whisper_get_timems();
    
    int result = whisper_full(ctx, wparams, pcm.data(), pcm.size());
    
    int64_t t_end = whisper_get_timems();
    
    if (result != 0) {
        fprintf(stderr, "Ошибка транскрибации: %d\n", result);
        whisper_free(ctx);
        return 1;
    }
    
    printf("\n Время обработки: %ld мс\n", t_end - t_start);
    printf("Результат:\n");
    
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        printf("%s", text);
    }
    printf("\n");
    
    // 6. Очистка
    whisper_free(ctx);
    printf("Готово\n");
    
    return 0;
}
