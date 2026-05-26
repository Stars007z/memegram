package com.example.memegram

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.memegram.data.gallery.GallerySection
import com.example.memegram.localization.S
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun DateScrubber(
    sections: List<GallerySection>,
    totalItems: Int,
    loadedItems: Int,
    gridState: LazyGridState,
    columns: Int = 3,
    modifier: Modifier = Modifier
) {
    if (totalItems <= 0) return

    val scope        = rememberCoroutineScope()
    var isDragging   by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var dragLabel    by remember { mutableStateOf("") }

    val scrollFraction by remember(totalItems) {
        derivedStateOf {
            val firstIdx = gridState.firstVisibleItemIndex
            if (totalItems <= 0) 0f
            else (firstIdx.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
        }
    }
    val thumbFraction = if (isDragging) dragFraction else scrollFraction

    val animatedFraction by animateFloatAsState(
        targetValue = thumbFraction,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scrubberThumbFraction"
    )

    BoxWithConstraints(modifier = modifier.width(64.sdp)) {
        val trackH = maxHeight

        val trackWidth by animateDpAsState(
            targetValue = if (isDragging) 8.sdp else 4.sdp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "scrubberTrackWidth"
        )
        val trackColor by animateColorAsState(
            targetValue = if (isDragging)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            else
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            label = "scrubberTrackColor"
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 7.sdp)
                .fillMaxHeight()
                .padding(vertical = 4.sdp)
                .width(trackWidth)
                .background(trackColor, RoundedCornerShape(trackWidth / 2))
        )

        val thumbW = 18.sdp
        val thumbH = 42.sdp
        val thumbScale by animateFloatAsState(
            targetValue = if (isDragging) 1.18f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "scrubberThumbScale"
        )

        val primary = MaterialTheme.colorScheme.primary
        val gradient = remember(primary) {
            Brush.verticalGradient(
                listOf(
                    primary.lighten(0.18f),
                    primary,
                    primary.darken(0.12f),
                )
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 1.sdp)
                .offset(
                    x = 0.sdp,
                    y = (trackH * animatedFraction - thumbH / 2)
                        .coerceIn(0.sdp, (trackH - thumbH).coerceAtLeast(0.sdp))
                )
                .scale(thumbScale)
                .shadow(
                    elevation = if (isDragging) 8.sdp else 4.sdp,
                    shape = RoundedCornerShape(50),
                    clip = false,
                    ambientColor = primary.copy(alpha = 0.4f),
                    spotColor    = primary.copy(alpha = 0.4f),
                )
                .size(width = thumbW, height = thumbH)
                .background(gradient, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.size(14.sdp)
            )
        }

        if (isDragging && dragLabel.isNotEmpty()) {
            val bubbleY = (trackH * animatedFraction - 16.sdp)
                .coerceIn(0.sdp, (trackH - 32.sdp).coerceAtLeast(0.sdp))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-22).sdp, y = bubbleY)
                    .wrapContentSize(align = Alignment.CenterEnd, unbounded = true)
                    .shadow(6.sdp, RoundedCornerShape(12.sdp), clip = false)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.82f),
                                Color.Black.copy(alpha = 0.72f),
                            )
                        ),
                        RoundedCornerShape(12.sdp)
                    )
                    .padding(horizontal = 12.sdp, vertical = 6.sdp)
            ) {
                Text(
                    text = dragLabel,
                    color = Color.White,
                    fontSize = 13.ssp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(sections, totalItems, loadedItems) {
                    fun handle(yPx: Float) {
                        if (size.height <= 0) return
                        val fraction = (yPx / size.height).coerceIn(0f, 1f)
                        dragFraction = fraction
                        val targetItem = (fraction * totalItems).toInt()
                            .coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                        dragLabel = sections.lastOrNull { it.firstItemIndex <= targetItem }?.label
                            ?: sections.firstOrNull()?.label
                            ?: ""
                        val scrollTarget = targetItem
                        scope.launch {
                            gridState.scrollToItem((scrollTarget / columns) * columns)
                        }
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent(PointerEventPass.Initial)
                                .changes
                                .firstOrNull { it.pressed }
                                ?: continue
                            down.consume()
                            isDragging = true
                            handle(down.position.y)

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (change.changedToUp() || !change.pressed) break
                                handle(change.position.y)
                            }
                            isDragging = false
                        }
                    }
                }
        )
    }
}

private fun Color.lighten(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (1f - red) * f,
        green = green + (1f - green) * f,
        blue = blue + (1f - blue) * f,
        alpha = alpha,
    )
}

private fun Color.darken(fraction: Float): Color {
    val f = (1f - fraction.coerceIn(0f, 1f))
    return Color(
        red = red * f,
        green = green * f,
        blue = blue * f,
        alpha = alpha,
    )
}

fun formatChatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""

    val tz       = TimeZone.currentSystemDefault()
    val msgLocal = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(tz)
    val nowLocal = Clock.System.now().toLocalDateTime(tz)

    val today  = nowLocal.date
    val msgDay = msgLocal.date

    return when {
        msgDay == today -> {
            "${msgLocal.hour.toString().padStart(2, '0')}:" +
                    msgLocal.minute.toString().padStart(2, '0')
        }
        msgDay >= today.minus(6, DateTimeUnit.DAY) -> {
            when (msgLocal.dayOfWeek) {
                DayOfWeek.MONDAY    -> S.current.mon
                DayOfWeek.TUESDAY   -> S.current.tue
                DayOfWeek.WEDNESDAY -> S.current.wed
                DayOfWeek.THURSDAY  -> S.current.thu
                DayOfWeek.FRIDAY    -> S.current.fri
                DayOfWeek.SATURDAY  -> S.current.sat
                DayOfWeek.SUNDAY    -> S.current.sun
            }
        }
        msgDay.year == today.year -> {
            "${msgDay.day.toString().padStart(2, '0')}." +
                    msgDay.month.number.toString().padStart(2, '0')
        }
        else -> {
            val yy = (msgDay.year % 100).toString().padStart(2, '0')
            "${msgDay.day.toString().padStart(2, '0')}." +
                    "${msgDay.month.number.toString().padStart(2, '0')}.$yy"
        }
    }
}
