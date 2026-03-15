cmake_minimum_required(VERSION 3.18.1)
project("VoiceTranslator")

# Добавляем whisper.cpp как подмодуль
add_subdirectory(src/main/cpp/whisper.cpp)

# Наша JNI библиотека
add_library(whisperjni SHARED src/main/cpp/whisperjni.cpp)

# Линкуем с whisper (не whisper.cpp, а целевую библиотеку)
target_link_libraries(whisperjni whisper android log)

# Оптимизации
target_compile_options(whisper PRIVATE -O3 -ffast-math -DNDEBUG)
