package com.example.memegram.data.gallery

import androidx.compose.runtime.Composable
import com.example.memegram.localization.S
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
    val s = S.current
    thumbs.forEachIndexed { index, thumb ->
        if (thumb.dateAdded > 0L) {
            val dt    = kotlin.time.Instant.fromEpochSeconds(thumb.dateAdded)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val label = "${s.monthShort(dt.month.number)} ${dt.year}"
            if (label != lastLabel) { result += GallerySection(label, index); lastLabel = label }
        }
    }
    return result
}

sealed class AttachItem {
    abstract val name: String
    open val asFile: Boolean get() = false
    data class FromPicker(
        val file: PlatformFile,
        override val asFile: Boolean = false
    ) : AttachItem() { override val name get() = file.name }
    data class FromGallery(val thumb: GalleryThumb) : AttachItem() { override val name get() = thumb.name }

    data class FromBytes(
        val bytes: ByteArray,
        override val name: String,
        val mime: String = "image/jpeg",
    ) : AttachItem() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FromBytes) return false
            return name == other.name && mime == other.mime && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int {
            var r = name.hashCode()
            r = 31 * r + mime.hashCode()
            r = 31 * r + bytes.contentHashCode()
            return r
        }
    }
}

interface GalleryLoader {
    val isPermissionGranted: Boolean
    fun requestPermission()
    suspend fun loadAll(): List<GalleryThumb>
    suspend fun loadThumbBytes(id: String): ByteArray?
    suspend fun loadRecent(limit: Int = 48): List<GalleryThumb> = loadAll().take(limit)
}

@Composable
expect fun rememberGalleryLoader(): GalleryLoader
