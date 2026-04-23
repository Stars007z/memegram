package com.example.memegram.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch

/**
 * Android implementation: thin wrapper around FileKit, which already uses
 * the system Photo Picker on Android 13+ and works correctly. We expose
 * the same `(List<ByteArray>) -> Unit` shape as iOS so call sites are
 * platform-agnostic.
 */
@Composable
actual fun rememberImagePicker(
    multiple: Boolean,
    onPicked: (List<ByteArray>) -> Unit,
): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()

    val singleLauncher = rememberFilePickerLauncher(
        type = PickerType.Image,
        mode = PickerMode.Single,
    ) { file ->
        scope.launch {
            val bytes = file?.readBytes()
            callback(if (bytes != null) listOf(bytes) else emptyList())
        }
    }
    val multiLauncher = rememberFilePickerLauncher(
        type = PickerType.Image,
        mode = PickerMode.Multiple(),
    ) { files ->
        scope.launch {
            val list = files?.map { it.readBytes() }.orEmpty()
            callback(list)
        }
    }

    return remember(multiple, singleLauncher, multiLauncher) {
        { if (multiple) multiLauncher.launch() else singleLauncher.launch() }
    }
}
