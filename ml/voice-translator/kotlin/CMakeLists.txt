cmake_minimum_required(VERSION 3.18.1)
project("VoiceTranslator" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -----------------------------------------------------------------------------
# Путь к исходникам whisper.cpp. Ожидается, что репозиторий подключён как
# git submodule:
#   git submodule add https://github.com/ggerganov/whisper.cpp src/main/cpp/whisper.cpp
# Переопределить можно через -DWHISPER_CPP_DIR=/custom/path.
# -----------------------------------------------------------------------------
set(WHISPER_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/whisper.cpp"
    CACHE PATH "Path to whisper.cpp sources")

if(NOT EXISTS "${WHISPER_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_CPP_DIR}\n"
        "Run: git submodule update --init --recursive\n"
        "or pass -DWHISPER_CPP_DIR=<path-to-whisper.cpp>")
endif()

# Отключаем ненужные Whisper-таргеты, чтобы не раздувать билд / APK.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE) # libomp обычно недоступна на Android

add_subdirectory(${WHISPER_CPP_DIR} whisper.cpp-build)

# JNI-обёртка
add_library(whisperjni SHARED
    src/main/cpp/whisperjni.cpp)

target_include_directories(whisperjni PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR})

target_link_libraries(whisperjni
    whisper
    android
    log)

target_compile_options(whisperjni PRIVATE
    -O3 -ffast-math -fvisibility=hidden)
