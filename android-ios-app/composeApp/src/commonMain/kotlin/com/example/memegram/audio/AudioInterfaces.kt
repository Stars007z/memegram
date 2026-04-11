package com.example.memegram.audio

data class AudioRecordResult(val bytes: ByteArray, val durationMs: Long, val waveform: String)

interface AudioRecorder {
    fun startRecording()
    fun pauseRecording()
    fun resumeRecording()
    fun stopRecording(waveform: String): AudioRecordResult?
    fun cancelRecording()
    fun getMaxAmplitude(): Int

    fun hasPermission(): Boolean
    fun requestPermission()
}

interface AudioPlayer {
    fun play(bytes: ByteArray, onCompletion: () -> Unit)
    fun pause()
    fun resume()
    fun stop()
    fun isPlaying(): Boolean
    fun isPaused(): Boolean
    fun getProgress(): Float
    fun getDurationMs(): Long
    fun seekTo(fraction: Float)
}

expect fun createAudioRecorder(): AudioRecorder
expect fun createAudioPlayer(): AudioPlayer