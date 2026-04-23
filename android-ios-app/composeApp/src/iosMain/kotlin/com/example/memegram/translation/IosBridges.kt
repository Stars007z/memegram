package com.example.memegram.translation

import kotlin.concurrent.AtomicReference

/**
 * Bridges populated from Swift on app launch (see iOSApp.swift).
 *
 * Swift implements the underlying functionality (ONNX Runtime, ZIPFoundation,
 * NLLanguageRecognizer) via SwiftPM packages, then registers a delegate
 * callback into Kotlin. Kotlin code calls the delegate instead of touching
 * the native libraries directly.
 *
 * Pattern mirrors [com.example.memegram.push.IosPushTokenBridge].
 */

/**
 * Output of one [OnnxBridge] inference call: a single tensor with float32
 * payload and its shape.
 */
class OnnxOutput(
    val data: FloatArray,
    val shape: LongArray,
)

/**
 * Native-side ONNX Runtime adapter. All session handles are opaque [Long]s
 * minted by the Swift side (e.g. pointers cast to UInt64).
 *
 * Methods are blocking; call from a background dispatcher.
 */
interface OnnxBridgeDelegate {
    /** Load model file at absolute [modelPath]. Returns opaque session handle. */
    fun loadSession(modelPath: String): Long

    /**
     * Last error message produced by [loadSession] (or null on success).
     * Surfaces ORT failures (e.g. OOM, unsupported op, missing file) to the
     * Kotlin side so they can be shown to the user instead of being swallowed.
     */
    val lastLoadError: String?

    /** Free a session previously returned by [loadSession]. Idempotent. */
    fun closeSession(handle: Long)

    /**
     * Run the session. All inputs and outputs are flat row-major float32
     * tensors plus a separate INT64 input list for token ids / masks.
     *
     * - [int64Names]/[int64Data]/[int64Shapes] supply integer tensors
     *   (input_ids, attention_mask, encoder_attention_mask).
     * - [floatNames]/[floatData]/[floatShapes] supply float tensors
     *   (encoder_hidden_states).
     * - [outputNames] is the ordered list of output tensor names to fetch.
     *
     * Returns an array aligned with [outputNames] containing [OnnxOutput]s.
     * Throws on any ORT failure.
     */
    fun run(
        handle: Long,
        int64Names: Array<String>,
        int64Data: Array<LongArray>,
        int64Shapes: Array<LongArray>,
        floatNames: Array<String>,
        floatData: Array<FloatArray>,
        floatShapes: Array<LongArray>,
        outputNames: Array<String>,
    ): Array<OnnxOutput>
}

object IosOnnxBridge {
    private val ref = AtomicReference<OnnxBridgeDelegate?>(null)

    fun register(delegate: OnnxBridgeDelegate) {
        ref.value = delegate
    }

    val delegate: OnnxBridgeDelegate?
        get() = ref.value

    fun isAvailable(): Boolean = ref.value != null

    fun require(): OnnxBridgeDelegate =
        ref.value ?: error("OnnxBridge not registered — Swift side must call IosOnnxBridge.register() at launch")
}

interface ZipBridgeDelegate {
    /**
     * Unzip [zipPath] into [destinationDir], flattening any subdirectories
     * (only the basename of each entry is kept). [destinationDir] must
     * already exist. Throws on any error.
     */
    fun unzip(zipPath: String, destinationDir: String)
}

object IosZipBridge {
    private val ref = AtomicReference<ZipBridgeDelegate?>(null)

    fun register(delegate: ZipBridgeDelegate) {
        ref.value = delegate
    }

    fun require(): ZipBridgeDelegate =
        ref.value ?: error("ZipBridge not registered — Swift side must call IosZipBridge.register() at launch")
}

interface LanguageIdBridgeDelegate {
    /**
     * Return BCP-47 dominant language code for [text], or null if not
     * confident enough.
     */
    fun identify(text: String): String?
}

object IosLanguageIdBridge {
    private val ref = AtomicReference<LanguageIdBridgeDelegate?>(null)

    fun register(delegate: LanguageIdBridgeDelegate) {
        ref.value = delegate
    }

    val delegate: LanguageIdBridgeDelegate?
        get() = ref.value
}

/**
 * Native iOS image picker, presented over the current root view controller
 * via PHPickerViewController. Multi-select supported, JPEG bytes returned
 * already decoded (HEIC → JPEG conversion done on the Swift side so the
 * common code never needs to deal with HEIC).
 *
 * [onResult] is invoked exactly once: with the picked images on success,
 * an empty list on cancel, or null on error (also empty list-equivalent).
 */
interface PhotoPickerBridgeDelegate {
    fun pick(multiple: Boolean, onResult: (List<ByteArray>) -> Unit)
}

object IosPhotoPickerBridge {
    private val ref = AtomicReference<PhotoPickerBridgeDelegate?>(null)

    fun register(delegate: PhotoPickerBridgeDelegate) {
        ref.value = delegate
    }

    val delegate: PhotoPickerBridgeDelegate?
        get() = ref.value
}
