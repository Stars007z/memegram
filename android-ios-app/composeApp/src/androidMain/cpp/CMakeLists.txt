cmake_minimum_required(VERSION 3.18.1)
project("MemegramWhisper" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp подключается как git submodule в эту же папку.
#   git submodule add https://github.com/ggerganov/whisper.cpp \
#       composeApp/src/androidMain/cpp/whisper.cpp
set(WHISPER_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp"
    CACHE PATH "Path to whisper.cpp sources")

if(NOT EXISTS "${WHISPER_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_CPP_DIR}\n"
        "Run: git submodule update --init --recursive\n"
        "or pass -DWHISPER_CPP_DIR=<path-to-whisper.cpp>")
endif()

# Не раздуваем APK ненужными таргетами.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_CPP_DIR} whisper.cpp-build)

add_library(whisperjni SHARED whisperjni.cpp)

target_include_directories(whisperjni PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR})

target_link_libraries(whisperjni
    whisper
    android
    log)

target_compile_options(whisperjni PRIVATE
    -O3 -ffast-math -fvisibility=hidden)
