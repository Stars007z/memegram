package com.example.memegram.picker

import androidx.compose.runtime.Composable

/**
 * Cross-platform image picker that returns raw image bytes (already decoded
 * to JPEG on iOS, untouched on Android — the receiving code crops/encodes
 * later anyway).
 *
 * Use this instead of `rememberFilePickerLauncher(PickerType.Image, ...)`
 * for image-only flows (avatars, covers, chat backgrounds, sending photos
 * in chat). Files / videos / arbitrary types still use FileKit.
 *
 * @param multiple allow selecting more than one image (Telegram-style grid
 *                 on iOS, multi-pick on Android Photo Picker).
 * @param onPicked called once per launch on the main thread; an empty list
 *                 means the user cancelled.
 * @return         a launcher: invoke to present the picker.
 */
@Composable
expect fun rememberImagePicker(
    multiple: Boolean = false,
    onPicked: (List<ByteArray>) -> Unit,
): () -> Unit
