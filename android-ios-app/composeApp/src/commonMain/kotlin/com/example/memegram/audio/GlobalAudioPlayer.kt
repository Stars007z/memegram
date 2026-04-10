package com.example.memegram.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GlobalAudioPlayer {

    // ── public state ──────────────────────────────────────────────────────
    data class State(
        val status: PlaybackStatus = PlaybackStatus.IDLE,
        val mediaId: String? = null,
        val chatName: String = "",
        val progress: Float = 0f,
        val durationMs: Long = 0L,
        val waveform: List<Int> = emptyList()
    )

    enum class PlaybackStatus { IDLE, PLAYING, PAUSED }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // ── internals ─────────────────────────────────────────────────────────
    private val player: AudioPlayer = createAudioPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    // ── public API ────────────────────────────────────────────────────────

    fun play(
        bytes: ByteArray,
        mediaId: String,
        chatName: String,
        durationMs: Long,
        waveform: List<Int>
    ) {
        stopInternal(notifyIdle = false)

        _state.value = State(
            status = PlaybackStatus.PLAYING,
            mediaId = mediaId,
            chatName = chatName,
            progress = 0f,
            durationMs = durationMs,
            waveform = waveform
        )

        player.play(bytes) {
            stopInternal(notifyIdle = true)
        }
        startProgressPolling()
    }

    fun pause() {
        if (_state.value.status != PlaybackStatus.PLAYING) return
        player.pause()
        progressJob?.cancel()
        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
    }

    fun resume() {
        if (_state.value.status != PlaybackStatus.PAUSED) return
        player.resume()
        _state.value = _state.value.copy(status = PlaybackStatus.PLAYING)
        startProgressPolling()
    }

    fun togglePlayPause() {
        when (_state.value.status) {
            PlaybackStatus.PLAYING -> pause()
            PlaybackStatus.PAUSED -> resume()
            PlaybackStatus.IDLE -> { /* nothing to toggle */ }
        }
    }

    fun seekTo(fraction: Float) {
        if (_state.value.status == PlaybackStatus.IDLE) return
        player.seekTo(fraction)
        _state.value = _state.value.copy(progress = fraction.coerceIn(0f, 1f))
    }

    fun stop() = stopInternal(notifyIdle = true)

    fun isActive(mediaId: String?): Boolean =
        mediaId != null && _state.value.mediaId == mediaId && _state.value.status != PlaybackStatus.IDLE

    // ── private helpers ───────────────────────────────────────────────────

    private fun stopInternal(notifyIdle: Boolean) {
        progressJob?.cancel()
        player.stop()
        if (notifyIdle) {
            _state.value = State()
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _state.value.status == PlaybackStatus.PLAYING) {
                delay(50)
                if (_state.value.status == PlaybackStatus.PLAYING) {
                    _state.value = _state.value.copy(progress = player.getProgress())
                }
            }
        }
    }
}
