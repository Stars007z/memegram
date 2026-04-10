package com.example.memegram.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.memegram.AppContextHolder
import java.io.File

actual fun createAudioRecorder(): AudioRecorder = AudioRecorderAndroid()
actual fun createAudioPlayer(): AudioPlayer = AudioPlayerAndroid()

class AudioRecorderAndroid : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun hasPermission(): Boolean {
        val context = AppContextHolder.context ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    override fun requestPermission() {
        val activity = AppContextHolder.context as? Activity ?: return
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    override fun startRecording() {
        val context = AppContextHolder.context ?: return
        if (!hasPermission()) return

        try {
            outputFile = File(context.filesDir, "voice_${System.currentTimeMillis()}.m4a")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            println("MemegramDebug [Voice-Android]: Ошибка старта: ${e.message}")
            recorder?.release()
            recorder = null
        }
    }

    @SuppressLint("NewApi")
    override fun pauseRecording() {
        try { recorder?.pause() } catch (_: Exception) {}
    }

    @SuppressLint("NewApi")
    override fun resumeRecording() {
        try { recorder?.resume() } catch (_: Exception) {}
    }

    override fun stopRecording(waveform: String): AudioRecordResult? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null

            val bytes = outputFile?.readBytes()
            outputFile?.delete()

            if (bytes == null || bytes.isEmpty()) return null

            AudioRecordResult(bytes, 0L, waveform)
        } catch (e: Exception) {
            println("MemegramDebug [Voice-Android]: Ошибка стопа: ${e.message}")
            null
        }
    }

    override fun cancelRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        outputFile?.delete()
    }

    override fun getMaxAmplitude(): Int = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
}

class AudioPlayerAndroid : AudioPlayer {
    private var player: MediaPlayer? = null
    private var tempFile: File? = null
    private var isPausedState = false
    private var onCompletionCallback: (() -> Unit)? = null

    override fun play(bytes: ByteArray, onCompletion: () -> Unit) {
        stop()

        onCompletionCallback = onCompletion
        val context = AppContextHolder.context
        tempFile = File(context.cacheDir, "temp_play.m4a").apply { writeBytes(bytes) }

        player = MediaPlayer().apply {
            setDataSource(tempFile!!.absolutePath)
            setOnCompletionListener {
                isPausedState = false
                onCompletionCallback?.invoke()
                onCompletionCallback = null
            }
            prepare()
            start()
        }
        isPausedState = false
    }

    override fun pause() {
        if (player?.isPlaying == true) {
            player?.pause()
            isPausedState = true
        }
    }

    override fun resume() {
        if (isPausedState && player != null) {
            player?.start()
            isPausedState = false
        }
    }

    override fun stop() {
        onCompletionCallback?.invoke()
        onCompletionCallback = null

        player?.stop()
        player?.release()
        player = null
        tempFile?.delete()
        isPausedState = false
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true
    override fun isPaused(): Boolean = isPausedState

    override fun getProgress(): Float {
        val p = player ?: return 0f
        if (p.duration == 0) return 0f
        return (p.currentPosition.toFloat() / p.duration.toFloat()).coerceIn(0f, 1f)
    }
}