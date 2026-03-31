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

    BoxWithConstraints(modifier = modifier.width(40.dp)) {
        val trackH = maxHeight

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(3.dp)
                .background(
                    if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    else Color.Gray.copy(alpha = 0.3f),
                    RoundedCornerShape(2.dp)
                )
        )

        if (isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 0.dp, y = trackH * dragFraction - 6.dp)
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }

        if (isDragging && dragLabel.isNotEmpty()) {
            Text(
                text     = dragLabel,
                color    = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = (-46).dp,
                        y = (trackH * dragFraction - 12.dp).coerceAtLeast(0.dp)
                    )
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
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
                DayOfWeek.MONDAY    -> "Пн"
                DayOfWeek.TUESDAY   -> "Вт"
                DayOfWeek.WEDNESDAY -> "Ср"
                DayOfWeek.THURSDAY  -> "Чт"
                DayOfWeek.FRIDAY    -> "Пт"
                DayOfWeek.SATURDAY  -> "Сб"
                DayOfWeek.SUNDAY    -> "Вс"
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