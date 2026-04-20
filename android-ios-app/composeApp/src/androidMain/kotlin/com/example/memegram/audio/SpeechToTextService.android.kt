package com.example.memegram.audio

import com.example.memegram.AppContextHolder

actual fun createSpeechToTextService(): SpeechToTextService =
    WhisperSpeechToTextService(AppContextHolder.context)
