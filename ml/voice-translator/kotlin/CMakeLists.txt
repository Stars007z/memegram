cmake_minimum_required(VERSION 3.18.1)

project("VoiceTranslator")

# Включаем whisper.cpp как подмодуль
add_subdirectory(src/main/cpp/whisper.cpp)

# Создаем JNI библиотеку
add_library(whisperjni SHARED
        src/main/cpp/whisperjni.cpp)

# Линкуем с whisper.cpp и системными библиотеками
target_link_libraries(whisperjni
        whisper
        android
        log)

# Оптимизации для мобильных
target_compile_options(whisper PRIVATE -O3 -ffast-math)
