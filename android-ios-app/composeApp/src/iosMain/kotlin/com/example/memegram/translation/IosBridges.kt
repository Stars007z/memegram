package com.example.memegram.translation

import kotlin.concurrent.AtomicReference

class OnnxOutput(
    val data: FloatArray,
    val shape: LongArray,
)

interface OnnxBridgeDelegate {
    fun loadSession(modelPath: String): Long

    val lastLoadError: String?

    fun closeSession(handle: Long)

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

    fun runWithUInt8(
        handle: Long,
        uint8Names: Array<String>,
        uint8Data: Array<ByteArray>,
        uint8Shapes: Array<LongArray>,
        outputNames: Array<String>,
    ): Array<OnnxOutput>

    fun setPersistentFloatInput(handle: Long, name: String, data: FloatArray, shape: LongArray): Boolean

    fun setPersistentInt64Input(handle: Long, name: String, data: LongArray, shape: LongArray): Boolean

    fun clearPersistentInputs(handle: Long)

    fun runArgmaxLastStep(
        handle: Long,
        int64Names: Array<String>,
        int64Data: Array<LongArray>,
        int64Shapes: Array<LongArray>,
        logitsOutputName: String,
        lastStepIndex: Int,
        vocabSize: Int,
    ): Int
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

interface FileOpenBridgeDelegate {
    fun open(path: String, mime: String): Boolean
}

object IosFileOpenBridge {
    private val ref = AtomicReference<FileOpenBridgeDelegate?>(null)

    fun register(delegate: FileOpenBridgeDelegate) {
        ref.value = delegate
    }

    val delegate: FileOpenBridgeDelegate?
        get() = ref.value
}
