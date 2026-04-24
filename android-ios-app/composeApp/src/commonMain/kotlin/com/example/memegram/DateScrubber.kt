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
    gridState: LazyGridState,
    columns: Int = 3,
    modifier: Modifier = Modifier
) {
    if (sections.size < 2 || totalItems == 0) return

    val scope        = rememberCoroutineScope()
    var isDragging   by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var dragLabel    by remember { mutableStateOf("") }

    BoxWithConstraints(modifier = modifier.width(40.sdp)) {
        val trackH = maxHeight

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(3.sdp)
                .then(
                    if (isDragging) Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        RoundedCornerShape(2.sdp)
                    ) else Modifier
                )
        )

        if (isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 0.sdp, y = trackH * dragFraction - 6.sdp)
                    .size(12.sdp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }

        if (isDragging && dragLabel.isNotEmpty()) {
            Text(
                text     = dragLabel,
                color    = Color.White,
                fontSize = 12.ssp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = (-46).sdp,
                        y = (trackH * dragFraction - 12.sdp).coerceAtLeast(0.sdp)
                    )
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.sdp))
                    .padding(horizontal = 8.sdp, vertical = 4.sdp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(sections, totalItems) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging   = true
                            val fraction = (offset.y / size.height).coerceIn(0f, 1f)
                            dragFraction = fraction
                            val targetItem = (fraction * totalItems).toInt().coerceIn(0, totalItems - 1)
                            dragLabel = sections.lastOrNull { it.firstItemIndex <= targetItem }?.label ?: ""
                            scope.launch { gridState.scrollToItem((targetItem / columns) * columns) }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                            dragFraction = fraction
                            val targetItem = (fraction * totalItems).toInt().coerceIn(0, totalItems - 1)
                            dragLabel = sections.lastOrNull { it.firstItemIndex <= targetItem }?.label ?: ""
                            scope.launch { gridState.scrollToItem((targetItem / columns) * columns) }
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