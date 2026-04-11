package com.example.memegram.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy

actual fun createAudioRecorder(): AudioRecorder = AudioRecorderIOS()
actual fun createAudioPlayer(): AudioPlayer = AudioPlayerIOS()

class AudioPlayerDelegate(private val onCompletion: () -> Unit) : NSObject(), AVAudioPlayerDelegateProtocol {
    override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
        onCompletion()
    }
}

class AudioRecorderIOS : AudioRecorder {
    private var recorder: AVAudioRecorder? = null
    private var recordUrl: NSURL? = null
    private var startTime: Long = 0

    override fun startRecording() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, null)
        session.setActive(true, null)

        val path = NSTemporaryDirectory() + "${NSUUID.UUID().UUIDString}.m4a"
        recordUrl = NSURL.fileURLWithPath(path)

        val settings = mapOf<Any?, Any>(AVFormatIDKey to kAudioFormatMPEG4AAC)

        recorder = AVAudioRecorder(recordUrl!!, settings, null)
        recorder?.record()
        startTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun stopRecording(): AudioRecordResult? {
        recorder?.stop()
        val duration = kotlin.time.Clock.System.now().toEpochMilliseconds() - startTime
        val data = NSData.dataWithContentsOfURL(recordUrl!!) ?: return null

        val bytes = ByteArray(data.length.toInt()).apply {
            usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }

        recorder = null
        return AudioRecordResult(bytes, duration)
    }

    override fun cancelRecording() {
        recorder?.stop()
        recorder = null
    }
}

class AudioPlayerIOS : AudioPlayer {
    private var player: AVAudioPlayer? = null
    private var delegate: AudioPlayerDelegate? = null
    private var isPausedState = false
    private var onCompletionCallback: (() -> Unit)? = null

    @OptIn(ExperimentalForeignApi::class)
    override fun play(bytes: ByteArray, onCompletion: () -> Unit) {
        stop()

        onCompletionCallback = onCompletion
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)

        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }

        delegate = AudioPlayerDelegate {
            isPausedState = false
            onCompletionCallback?.invoke()
            onCompletionCallback = null
        }
        player = AVAudioPlayer(data, null)
        player?.delegate = delegate
        player?.play()
        isPausedState = false
    }

    override fun pause() {
        if (player?.isPlaying() == true) {
            player?.pause()
            isPausedState = true
        }
    }

    override fun resume() {
        if (isPausedState && player != null) {
            player?.play()
            isPausedState = false
        }
    }

    override fun stop() {
        onCompletionCallback = null

        player?.stop()
        player = null
        delegate = null
        isPausedState = false
    }

    override fun isPlaying(): Boolean = player?.isPlaying() ?: false
    override fun isPaused(): Boolean = isPausedState

    override fun getProgress(): Float {
        val p = player ?: return 0f
        if (p.duration == 0.0) return 0f
        return (p.currentTime / p.duration).toFloat().coerceIn(0f, 1f)
    }

    override fun getDurationMs(): Long {
        val p = player ?: return 0L
        return (p.duration * 1000.0).toLong()
    }

    override fun seekTo(fraction: Float) {
        val p = player ?: return
        p.currentTime = (fraction.coerceIn(0f, 1f).toDouble() * p.duration)
    }
}