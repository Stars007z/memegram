package com.example.memegram.data.gallery

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.core.PlatformFile
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

data class GalleryThumb(
    val id: String,
    val bytes: ByteArray,
    val name: String,
    val dateAdded: Long = 0L
) {
    override fun equals(other: Any?) = other is GalleryThumb && id == other.id
    override fun hashCode() = id.hashCode()
}

data class GallerySection(val label: String, val firstItemIndex: Int)

fun buildGallerySections(thumbs: List<GalleryThumb>): List<GallerySection> {
    val result = mutableListOf<GallerySection>()
    var lastLabel = ""
    thumbs.forEachIndexed { index, thumb ->
        if (thumb.dateAdded > 0L) {
            val dt    = kotlin.time.Instant.fromEpochSeconds(thumb.dateAdded)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val label = "${monthShort(dt.month.number)} ${dt.year}"
            if (label != lastLabel) { result += GallerySection(label, index); lastLabel = label }
        }
    }
    return result
}

private fun monthShort(m: Int) = when (m) {
    1 -> "Янв"; 2 -> "Фев"; 3 -> "Мар"; 4 -> "Апр"
    5 -> "Май"; 6 -> "Июн"; 7 -> "Июл"; 8 -> "Авг"
    9 -> "Сен"; 10 -> "Окт"; 11 -> "Ноя"; else -> "Дек"
}

sealed class AttachItem {
    abstract val name: String
    data class FromPicker(val file: PlatformFile) : AttachItem() { override val name get() = file.name }
    data class FromGallery(val thumb: GalleryThumb) : AttachItem() { override val name get() = thumb.name }
}

interface GalleryLoader {
    val isPermissionGranted: Boolean
    fun requestPermission()
    suspend fun loadRecent(limit: Int = 48): List<GalleryThumb>
}

@Composable
expect fun rememberGalleryLoader(): GalleryLoader