package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.data.gallery.GallerySection
import com.example.memegram.localization.S
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp

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

    val currentLabel by remember {
        derivedStateOf {
            val firstIdx = gridState.firstVisibleItemIndex
            sections.lastOrNull { it.firstItemIndex <= firstIdx }?.label ?: ""
        }
    }

    BoxWithConstraints(modifier = modifier.width(56.sdp)) {
        val trackH = maxHeight

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(if (isDragging) 5.sdp else 3.sdp)
                .background(
                    if (isDragging)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    RoundedCornerShape(3.sdp)
                )
        )

        val thumbSize = if (isDragging) 22.sdp else 16.sdp
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(
                    x = 0.sdp,
                    y = (trackH * thumbFraction - thumbSize / 2).coerceAtLeast(0.sdp)
                )
                .size(thumbSize)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )

        if (isDragging && dragLabel.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = (-40).sdp,
                        y = (trackH * dragFraction - 16.sdp).coerceAtLeast(0.sdp)
                    )
                    .wrapContentSize(align = Alignment.CenterEnd, unbounded = true)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.sdp))
                    .padding(horizontal = 12.sdp, vertical = 6.sdp)
            ) {
                Text(
                    text     = dragLabel,
                    color    = Color.White,
                    fontSize = 13.ssp,
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
                        val fraction = (yPx / size.height).coerceIn(0f, 1f)
                        dragFraction = fraction
                        val targetItem = (fraction * totalItems).toInt().coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                        dragLabel = sections.lastOrNull { it.firstItemIndex <= targetItem }?.label
                            ?: sections.firstOrNull()?.label
                            ?: ""
                        val loaded = loadedItems.coerceAtLeast(1)
                        val scrollTarget = targetItem.coerceAtMost(loaded - 1)
                        scope.launch { gridState.scrollToItem((scrollTarget / columns) * columns) }
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            handle(offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            handle(change.position.y)
                        },
                        onDragEnd    = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
        )
    }
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
