package com.example.memegram.nsfw

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NsfwSettings(private val settings: Settings) {

    companion object {
        private const val KEY_FILTER_ENABLED = "privacy_nsfw_filter_enabled"
        private const val KEY_FILTER_REVISION = "privacy_nsfw_filter_revision"
        private const val KEY_PROCESSED_MEDIA_IDS = "privacy_nsfw_processed_media_ids_v2"
        private const val MAX_PROCESSED_MEDIA_IDS = 5_000
    }

    private val _filterEnabled = MutableStateFlow(
        settings.getBoolean(KEY_FILTER_ENABLED, false)
    )
    val filterEnabled: StateFlow<Boolean> = _filterEnabled.asStateFlow()

    private val _filterRevision = MutableStateFlow(
        settings.getInt(KEY_FILTER_REVISION, 0)
    )
    val filterRevision: StateFlow<Int> = _filterRevision.asStateFlow()

    private val _processedMediaIds = MutableStateFlow(loadProcessedMediaIds())

    fun setFilterEnabled(enabled: Boolean) {
        if (_filterEnabled.value == enabled) return
        settings.putBoolean(KEY_FILTER_ENABLED, enabled)
        _filterEnabled.value = enabled
        bumpFilterRevision()
    }

    fun notifyModelStateChanged() {
        bumpFilterRevision()
    }

    fun hasProcessedMedia(mediaId: String): Boolean {
        return mediaId in _processedMediaIds.value
    }

    fun markMediaProcessed(mediaId: String) {
        if (mediaId.isBlank()) return
        val ordered = _processedMediaIds.value.toMutableList()
        ordered.remove(mediaId)
        ordered.add(mediaId)
        val capped = ordered.takeLast(MAX_PROCESSED_MEDIA_IDS).toSet()
        settings.putString(KEY_PROCESSED_MEDIA_IDS, capped.joinToString(","))
        _processedMediaIds.value = capped
    }

    fun unmarkMediaProcessed(mediaId: String) {
        if (mediaId.isBlank() || mediaId !in _processedMediaIds.value) return
        val updated = _processedMediaIds.value - mediaId
        settings.putString(KEY_PROCESSED_MEDIA_IDS, updated.joinToString(","))
        _processedMediaIds.value = updated
    }

    private fun bumpFilterRevision() {
        val next = _filterRevision.value + 1
        settings.putInt(KEY_FILTER_REVISION, next)
        _filterRevision.value = next
    }

    private fun loadProcessedMediaIds(): Set<String> {
        val raw = settings.getString(KEY_PROCESSED_MEDIA_IDS, "")
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeLast(MAX_PROCESSED_MEDIA_IDS)
            .toSet()
    }
}
