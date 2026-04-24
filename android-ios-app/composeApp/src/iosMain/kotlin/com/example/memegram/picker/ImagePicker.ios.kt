package com.example.memegram.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.example.memegram.translation.IosPhotoPickerBridge

@Composable
actual fun rememberImagePicker(
    multiple: Boolean,
    onPicked: (List<ByteArray>) -> Unit,
): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    return remember(multiple) {
        {
            val bridge = IosPhotoPickerBridge.delegate
            if (bridge == null) {
                println("[ImagePicker.ios] PhotoPickerBridge not registered — picker disabled")
                callback(emptyList())
            } else {
                bridge.pick(multiple = multiple) { bytes -> callback(bytes) }
            }
        }
    }
}
