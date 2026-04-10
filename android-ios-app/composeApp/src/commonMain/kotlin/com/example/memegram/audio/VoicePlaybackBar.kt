package com.example.memegram.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VoicePlaybackBar(
    state: GlobalAudioPlayer.State,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val isVisible = state.status != GlobalAudioPlayer.PlaybackStatus.IDLE

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val isPlaying = state.status == GlobalAudioPlayer.PlaybackStatus.PLAYING
        val playedColor = accentColor
        val unplayedColor = accentColor.copy(alpha = 0.25f)
        val textColor = MaterialTheme.colorScheme.onSurface

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // ── play / pause ──
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f))
                    .clickable { onTogglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // ── waveform (scrubbable) ──
            val amplitudes = state.waveform.ifEmpty { List(30) { 1 } }

            Box(modifier = Modifier.weight(1f).height(28.dp)) {
                ScrubbableWaveform(
                    amplitudes = amplitudes,
                    progress = state.progress,
                    playedColor = playedColor,
                    unplayedColor = unplayedColor,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.width(8.dp))

            // ── time label ──
            val currentMs = (state.durationMs * state.progress).toLong()
            val timeText = "${formatMs(currentMs)} / ${formatMs(state.durationMs)}"
            Text(
                text = timeText,
                color = textColor.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.width(8.dp))

            // ── close button ──
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── helpers ──────────────────────────────────────────────────────────────

@Composable
private fun ScrubbableWaveform(
    amplitudes: List<Int>,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val barWidth = 3.dp.toPx()
        val spacing = 2.dp.toPx()
        val maxBars = (size.width / (barWidth + spacing)).toInt()

        val displayAmps = if (amplitudes.size > maxBars) amplitudes.takeLast(maxBars) else amplitudes

        displayAmps.forEachIndexed { index, amp ->
            val barHeight = maxOf(4.dp.toPx(), (amp / 9f) * size.height)
            val x = index * (barWidth + spacing)
            val isPlayed = (index.toFloat() / displayAmps.size) <= progress

            drawRoundRect(
                color = if (isPlayed) playedColor else unplayedColor,
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
