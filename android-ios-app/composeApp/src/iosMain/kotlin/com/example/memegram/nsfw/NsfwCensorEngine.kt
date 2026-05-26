package com.example.memegram.nsfw

import com.example.memegram.translation.IosOnnxBridge
import com.example.memegram.translation.OnnxBridgeDelegate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.dataWithBytes
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
class NsfwCensorEngine private constructor(
    private val bridge: OnnxBridgeDelegate,
    private val mainModelPath: String,
    private val nudeNetModelPath: String,
    private val animeCensorModelPath: String,
    private val owlSwastikaModelPath: String,
) {

    companion object {
        private const val MAX_ANALYSIS_DIM = 1280
        private const val MAX_BLUR_WORK_DIM = 1024
        private const val DENSE_PIXELATE_BASE = 8
        private const val DENSE_BLUR_PASSES = 5
        private const val DENSE_BLUR_RADIUS_DIVISOR = 14
        private const val SESSION_IDLE_TIMEOUT_MS = 60_000L

        private const val MAIN_MODEL_SIZE = 336
        private const val MAIN_INPUT_NAME = "image"
        private const val MAIN_OUTPUT_NAME = "class_prediction"
        private const val MAIN_NEGATIVE_LABEL_INDEX = 5
        private const val MAIN_NUDITY_LABEL_INDEX = 3
        private const val NUDITY_FALLBACK_THRESHOLD = 0.23f

        private const val MAIN_ALCOHOL_INDEX = 0
        private const val MAIN_GORE_INDEX = 1
        private const val MAIN_MILITARY_INDEX = 2
        private const val MAIN_SMOKING_INDEX = 4

        private const val SLIDING_TRIGGER_THRESHOLD = 0.23f
        private const val SLIDING_PER_WINDOW_THRESHOLD = 0.30f
        private const val SLIDING_WINDOW_GRID = 3
        private const val SLIDING_WINDOW_FRACTION = 0.30f
        private const val SLIDING_FULL_BLUR_RATIO = 0.85f
        private const val SLIDING_FULL_BLUR_MIN_SCORE = 0.35f

        private const val NUDENET_SIZE = 320
        private const val ANIME_SIZE = 640
        private const val OWL_SIZE = 960
        private const val DETECTOR_INPUT_NAME = "images"
        private const val DETECTOR_OUTPUT_NAME = "output0"
        private const val OWL_INPUT_NAME = "pixel_values"
        private const val OWL_LOGITS_OUTPUT_NAME = "logits"
        private const val OWL_BOXES_OUTPUT_NAME = "pred_boxes"
        private const val NUDENET_CONFIDENCE = 0.30f
        private const val ANIME_CONFIDENCE = 0.30f
        private const val OWL_CONFIDENCE = 0.10f
        private const val OWL_MAX_AREA_FRACTION = 0.65f
        private const val OWL_PADDING_FRACTION = 0.20f
        private const val DETECTOR_PADDING_FRACTION = 0.15f
        private const val NMS_IOU_THRESHOLD = 0.45f

        private val NUDENET_LABELS = arrayOf(
            "FEMALE_GENITALIA_COVERED",
            "FACE_FEMALE",
            "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED",
            "MALE_BREAST_EXPOSED",
            "ANUS_EXPOSED",
            "FEET_EXPOSED",
            "BELLY_COVERED",
            "FEET_COVERED",
            "ARMPITS_COVERED",
            "ARMPITS_EXPOSED",
            "FACE_MALE",
            "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "ANUS_COVERED",
            "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED",
        )

        private val NUDENET_BLUR_LABELS = setOf(
            "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED",
            "ANUS_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "MALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_COVERED",
        )

        private val ANIME_LABELS = arrayOf("nipple_f", "penis", "pussy")
        private val ANIME_BLUR_LABELS = ANIME_LABELS.toSet()

        private val SLIDING_TRIGGER_CLASSES = listOf(
            MAIN_SMOKING_INDEX to "smoking",
            MAIN_ALCOHOL_INDEX to "alcohol",
            MAIN_GORE_INDEX to "gore",
        )

        fun load(
            mainModelPath: String,
            nudeNetModelPath: String,
            animeCensorModelPath: String,
            owlSwastikaModelPath: String,
        ): NsfwCensorEngine {
            val bridge = IosOnnxBridge.require()
            return NsfwCensorEngine(
                bridge = bridge,
                mainModelPath = mainModelPath,
                nudeNetModelPath = nudeNetModelPath,
                animeCensorModelPath = animeCensorModelPath,
                owlSwastikaModelPath = owlSwastikaModelPath,
            )
        }

        private fun loadSessionOrThrow(bridge: OnnxBridgeDelegate, path: String): Long {
            val handle = bridge.loadSession(path)
            if (handle == 0L) {
                val why = bridge.lastLoadError ?: "unknown error"
                error("ORT loadSession failed for $path: $why")
            }
            return handle
        }
    }

    private val sessionCache = mutableMapOf<String, CachedSession>()
    private val sessionCacheLock = SynchronizedObject()

    suspend fun censorImageIfNeeded(
        imageBytes: ByteArray,
        mime: String?,
    ): NsfwCensorResult = withContext(Dispatchers.Default) {
        val image = decodeImage(imageBytes)
            ?: error("Failed to decode image for NSFW classification")
        val analysis = createAnalysisImage(image)

        try {
            val mainScores = classifyMainScores(analysis)
            val prediction = mainScores.indices.maxByOrNull { mainScores[it] } ?: MAIN_NUDITY_LABEL_INDEX
            println("[NSFW-iOS] censorImageIfNeeded(): mainPrediction=$prediction scores=${mainScores.joinToString(prefix = "[", postfix = "]") { it.toString() }}")

            val detections = detectRegions(analysis).map { detection ->
                detection.copy(
                    rect = scaleRect(
                        rect = detection.rect,
                        scaleX = image.width.toFloat() / analysis.width.toFloat(),
                        scaleY = image.height.toFloat() / analysis.height.toFloat(),
                        imageW = image.width,
                        imageH = image.height,
                    ),
                )
            }.toMutableList()

            val slidingTriggers = SLIDING_TRIGGER_CLASSES.filter { (idx, _) -> mainScores.getOrElse(idx) { 0f } >= SLIDING_TRIGGER_THRESHOLD }
            if (slidingTriggers.isNotEmpty()) {
                val targetIndices = slidingTriggers.map { it.first }
                val sliding = runSlidingWindow(analysis, targetIndices)
                if (sliding.fullFrame) {
                    println("[NSFW-iOS] censorImageIfNeeded(): sliding window escalated to full-frame (target=$targetIndices)")
                    detections.clear()
                    detections += Detection(
                        rect = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                        score = sliding.maxScore,
                        source = "sliding-full",
                    )
                } else if (sliding.regions.isNotEmpty()) {
                    println("[NSFW-iOS] censorImageIfNeeded(): sliding window adds ${sliding.regions.size} region(s) (target=$targetIndices)")
                    sliding.regions.forEach { rect ->
                        detections += Detection(
                            rect = scaleRect(
                                rect = rect,
                                scaleX = image.width.toFloat() / analysis.width.toFloat(),
                                scaleY = image.height.toFloat() / analysis.height.toFloat(),
                                imageW = image.width,
                                imageH = image.height,
                            ),
                            score = sliding.maxScore,
                            source = "sliding",
                        )
                    }
                }
            }

            if (detections.isNotEmpty()) {
                val merged = nms(detections, NMS_IOU_THRESHOLD)
                println("[NSFW-iOS] censorImageIfNeeded(): blurring ${merged.size} region(s)")
                merged.forEachIndexed { i, d ->
                    val pctW = ((d.rect.width / image.width.toFloat()) * 100f).toInt()
                    val pctH = ((d.rect.height / image.height.toFloat()) * 100f).toInt()
                    val scoreStr = ((d.score * 100f).toInt() / 100f).toString()
                    println("[NSFW-iOS]   detect[$i] src=${d.source} score=$scoreStr rect=L=${d.rect.left.toInt()} T=${d.rect.top.toInt()} R=${d.rect.right.toInt()} B=${d.rect.bottom.toInt()} (${pctW}%x${pctH}%)")
                }

                val nudityScore = mainScores.getOrElse(MAIN_NUDITY_LABEL_INDEX) { 0f }
                val hasNudityRegion = merged.any { it.source == "nudenet" || it.source == "anime" }
                if (nudityScore >= NUDITY_FALLBACK_THRESHOLD && !hasNudityRegion) {
                    println("[NSFW-iOS] censorImageIfNeeded(): nudity fallback triggered (score=$nudityScore >= $NUDITY_FALLBACK_THRESHOLD, no nudity-specific region among ${merged.size} detection(s)), full-frame blur")
                    val fullRect = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
                    return@withContext NsfwCensorResult(
                        bytes = blurRegions(image, listOf(fullRect), mime),
                        processed = true,
                    )
                }

                return@withContext NsfwCensorResult(
                    bytes = blurRegions(image, merged.map { it.rect }, mime),
                    processed = true,
                )
            }

            val nudityScore = mainScores.getOrElse(MAIN_NUDITY_LABEL_INDEX) { 0f }
            if (nudityScore >= NUDITY_FALLBACK_THRESHOLD) {
                println("[NSFW-iOS] censorImageIfNeeded(): nudity fallback triggered (score=$nudityScore >= $NUDITY_FALLBACK_THRESHOLD, no detections), full-frame blur")
                val fullRect = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
                return@withContext NsfwCensorResult(
                    bytes = blurRegions(image, listOf(fullRect), mime),
                    processed = true,
                )
            }

            NsfwCensorResult(imageBytes, processed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[NSFW-iOS] censorImageIfNeeded(): inference failed ${e::class.simpleName}: ${e.message}")
            NsfwCensorResult(imageBytes, processed = false)
        } finally {
            maybeEvictIdleSessions()
        }
    }

    private fun detectRegions(image: ImagePixels): List<Detection> {
        val nudeNet = withSession(nudeNetModelPath) { session -> runDetector(
            session = session,
            image = image,
            modelSize = NUDENET_SIZE,
            confidenceThreshold = NUDENET_CONFIDENCE,
            labels = NUDENET_LABELS,
            blurLabels = NUDENET_BLUR_LABELS,
            paddingMode = PaddingMode.TOP_LEFT_SQUARE,
        ) }.map { it.copy(source = "nudenet") }
        val anime = withSession(animeCensorModelPath) { session -> runDetector(
            session = session,
            image = image,
            modelSize = ANIME_SIZE,
            confidenceThreshold = ANIME_CONFIDENCE,
            labels = ANIME_LABELS,
            blurLabels = ANIME_BLUR_LABELS,
            paddingMode = PaddingMode.CENTER_LETTERBOX,
        ) }.map { it.copy(source = "anime") }
        val swastika = withSession(owlSwastikaModelPath) { session -> runOwlSwastikaDetector(session, image) }.map { it.copy(source = "owl") }
        println("[NSFW-iOS] detectRegions(): nudenet=${nudeNet.size} anime=${anime.size} owl=${swastika.size}")
        return nms(nudeNet + anime + swastika, NMS_IOU_THRESHOLD)
    }

    private fun classifyMainScores(image: ImagePixels): FloatArray = runMainClassifier(image)

    private fun runMainClassifier(image: ImagePixels): FloatArray {
        val input = imageToMainRgb(image)
        return withSession(mainModelPath) { session ->
            val outputs = bridge.runWithUInt8(
                handle = session,
                uint8Names = arrayOf(MAIN_INPUT_NAME),
                uint8Data = arrayOf(input),
                uint8Shapes = arrayOf(longArrayOf(1, MAIN_MODEL_SIZE.toLong(), MAIN_MODEL_SIZE.toLong(), 3)),
                outputNames = arrayOf(MAIN_OUTPUT_NAME),
            )
            val scores = outputs.firstOrNull()?.data?.take(6)?.toFloatArray() ?: FloatArray(0)
            scores
        }
    }

    private fun <T> withSession(modelPath: String, block: (Long) -> T): T {
        val cached = synchronized(sessionCacheLock) {
            val existing = sessionCache[modelPath]
            if (existing != null) {
                existing.lastUsedAt = currentTimeMillis()
                existing
            } else {
                val newHandle = loadSessionOrThrow(bridge, modelPath)
                val entry = CachedSession(handle = newHandle, modelPath = modelPath, lastUsedAt = currentTimeMillis())
                sessionCache[modelPath] = entry
                entry
            }
        }
        return try {
            block(cached.handle)
        } finally {
            synchronized(sessionCacheLock) { cached.lastUsedAt = currentTimeMillis() }
        }
    }

    private fun maybeEvictIdleSessions() {
        val now = currentTimeMillis()
        val toClose = mutableListOf<CachedSession>()
        synchronized(sessionCacheLock) {
            val iterator = sessionCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastUsedAt >= SESSION_IDLE_TIMEOUT_MS) {
                    toClose += entry.value
                    iterator.remove()
                }
            }
        }
        toClose.forEach { cached ->
            runCatching { bridge.closeSession(cached.handle) }
            println("[NSFW-iOS] maybeEvictIdleSessions(): closed idle session ${cached.modelPath.substringAfterLast('/')}")
        }
    }

    private fun runSlidingWindow(image: ImagePixels, targetClassIndices: List<Int>): SlidingWindowResult {
        val w = image.width
        val h = image.height
        val winW = max((w * SLIDING_WINDOW_FRACTION).toInt(), 64).coerceAtMost(w)
        val winH = max((h * SLIDING_WINDOW_FRACTION).toInt(), 64).coerceAtMost(h)
        val grid = SLIDING_WINDOW_GRID
        val stepX = if (grid > 1) (w - winW).toFloat() / (grid - 1).toFloat() else 0f
        val stepY = if (grid > 1) (h - winH).toFloat() / (grid - 1).toFloat() else 0f

        val totalScans = grid * grid
        var triggered = 0
        var maxScore = 0f
        val regions = mutableListOf<RectF>()

        for (row in 0 until grid) {
            for (col in 0 until grid) {
                val x = (col * stepX).toInt().coerceIn(0, w - winW)
                val y = (row * stepY).toInt().coerceIn(0, h - winH)
                val crop = cropImage(image, x, y, winW, winH)
                val scores = runMainClassifier(crop)
                val cropScore = targetClassIndices.maxOf { scores.getOrElse(it) { 0f } }
                if (cropScore > maxScore) maxScore = cropScore
                if (cropScore >= SLIDING_PER_WINDOW_THRESHOLD) {
                    triggered++
                    regions += RectF(x.toFloat(), y.toFloat(), (x + winW).toFloat(), (y + winH).toFloat())
                }
            }
        }

        val ratio = triggered.toFloat() / totalScans.toFloat()
        println("[NSFW-iOS] runSlidingWindow(): triggered=$triggered/$totalScans ratio=$ratio maxScore=$maxScore")

        if (ratio >= SLIDING_FULL_BLUR_RATIO && maxScore >= SLIDING_FULL_BLUR_MIN_SCORE) {
            return SlidingWindowResult(fullFrame = true, regions = emptyList(), maxScore = maxScore)
        }
        return SlidingWindowResult(fullFrame = false, regions = regions, maxScore = maxScore)
    }

    private fun cropImage(source: ImagePixels, x: Int, y: Int, w: Int, h: Int): ImagePixels {
        val out = ByteArray(w * h * 4)
        for (row in 0 until h) {
            val srcStart = ((y + row) * source.width + x) * 4
            val dstStart = row * w * 4
            source.pixels.copyInto(out, dstStart, srcStart, srcStart + w * 4)
        }
        return ImagePixels(w, h, out)
    }

    private fun runDetector(
        session: Long,
        image: ImagePixels,
        modelSize: Int,
        confidenceThreshold: Float,
        labels: Array<String>,
        blurLabels: Set<String>,
        paddingMode: PaddingMode,
    ): List<Detection> {
        val input = prepareDetectorInput(image, modelSize, paddingMode)
        val outputs = bridge.run(
            handle = session,
            int64Names = emptyArray(),
            int64Data = emptyArray(),
            int64Shapes = emptyArray(),
            floatNames = arrayOf(DETECTOR_INPUT_NAME),
            floatData = arrayOf(input.data),
            floatShapes = arrayOf(longArrayOf(1, 3, modelSize.toLong(), modelSize.toLong())),
            outputNames = arrayOf(DETECTOR_OUTPUT_NAME),
        )
        val output = outputs.firstOrNull() ?: return emptyList()
        return decodeDetectorOutput(
            outputData = output.data,
            outputShape = output.shape,
            input = input,
            labels = labels,
            blurLabels = blurLabels,
            confidenceThreshold = confidenceThreshold,
        )
    }

    private fun prepareDetectorInput(
        image: ImagePixels,
        modelSize: Int,
        paddingMode: PaddingMode,
    ): DetectorInput {
        val originalW = image.width
        val originalH = image.height
        val scale: Float
        val padX: Float
        val padY: Float
        val scaledW: Int
        val scaledH: Int
        val background: Int

        when (paddingMode) {
            PaddingMode.TOP_LEFT_SQUARE -> {
                val maxSize = maxOf(originalW, originalH)
                scale = modelSize.toFloat() / maxSize.toFloat()
                scaledW = (originalW * scale).toInt().coerceIn(1, modelSize)
                scaledH = (originalH * scale).toInt().coerceIn(1, modelSize)
                padX = 0f
                padY = 0f
                background = 0x000000
            }

            PaddingMode.CENTER_LETTERBOX -> {
                scale = minOf(
                    modelSize.toFloat() / originalW.toFloat(),
                    modelSize.toFloat() / originalH.toFloat(),
                )
                scaledW = (originalW * scale).toInt().coerceIn(1, modelSize)
                scaledH = (originalH * scale).toInt().coerceIn(1, modelSize)
                padX = (modelSize - scaledW) / 2f
                padY = (modelSize - scaledH) / 2f
                background = (114 shl 16) or (114 shl 8) or 114
            }
        }

        val plane = modelSize * modelSize
        val data = FloatArray(plane * 3)
        for (y in 0 until modelSize) {
            for (x in 0 until modelSize) {
                val inside = x.toFloat() >= padX &&
                        y.toFloat() >= padY &&
                        x.toFloat() < padX + scaledW &&
                        y.toFloat() < padY + scaledH
                val rgb = if (inside) {
                    val sx = ((x + 0.5f - padX) / scale) - 0.5f
                    val sy = ((y + 0.5f - padY) / scale) - 0.5f
                    sampleRgb(image, sx, sy, background)
                } else {
                    background
                }
                val index = y * modelSize + x
                data[index] = ((rgb shr 16) and 0xFF) / 255f
                data[plane + index] = ((rgb shr 8) and 0xFF) / 255f
                data[plane * 2 + index] = (rgb and 0xFF) / 255f
            }
        }

        return DetectorInput(
            data = data,
            originalW = originalW,
            originalH = originalH,
            scale = scale,
            padX = padX,
            padY = padY,
        )
    }

    private fun runOwlSwastikaDetector(session: Long, image: ImagePixels): List<Detection> {
        val input = prepareOwlInput(image)
        val outputs = bridge.run(
            handle = session,
            int64Names = emptyArray(),
            int64Data = emptyArray(),
            int64Shapes = emptyArray(),
            floatNames = arrayOf(OWL_INPUT_NAME),
            floatData = arrayOf(input.data),
            floatShapes = arrayOf(longArrayOf(1, 3, OWL_SIZE.toLong(), OWL_SIZE.toLong())),
            outputNames = arrayOf(OWL_LOGITS_OUTPUT_NAME, OWL_BOXES_OUTPUT_NAME),
        )
        if (outputs.size < 2) return emptyList()
        return decodeOwlOutput(
            logits = outputs[0].data,
            logitsShape = outputs[0].shape,
            boxes = outputs[1].data,
            input = input,
            confidenceThreshold = OWL_CONFIDENCE,
        )
    }

    private fun prepareOwlInput(image: ImagePixels): DetectorInput {
        val originalW = image.width
        val originalH = image.height
        val paddedSize = maxOf(originalW, originalH)
        val scale = OWL_SIZE.toFloat() / paddedSize.toFloat()
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val background = (128 shl 16) or (128 shl 8) or 128
        val plane = OWL_SIZE * OWL_SIZE
        val data = FloatArray(plane * 3)

        for (y in 0 until OWL_SIZE) {
            for (x in 0 until OWL_SIZE) {
                val inside = x.toFloat() < originalW * scale && y.toFloat() < originalH * scale
                val rgb = if (inside) {
                    val sx = ((x + 0.5f) / scale) - 0.5f
                    val sy = ((y + 0.5f) / scale) - 0.5f
                    sampleRgb(image, sx, sy, background)
                } else {
                    background
                }
                val index = y * OWL_SIZE + x
                val r = ((rgb shr 16) and 0xFF) / 255f
                val g = ((rgb shr 8) and 0xFF) / 255f
                val b = (rgb and 0xFF) / 255f
                data[index] = (r - mean[0]) / std[0]
                data[plane + index] = (g - mean[1]) / std[1]
                data[plane * 2 + index] = (b - mean[2]) / std[2]
            }
        }

        return DetectorInput(
            data = data,
            originalW = originalW,
            originalH = originalH,
            scale = scale,
            padX = 0f,
            padY = 0f,
        )
    }

    private fun decodeOwlOutput(
        logits: FloatArray,
        logitsShape: LongArray,
        boxes: FloatArray,
        input: DetectorInput,
        confidenceThreshold: Float,
    ): List<Detection> {
        if (logitsShape.size < 3 || logits.isEmpty() || boxes.isEmpty()) return emptyList()
        val anchors = logitsShape[1].toInt()
        val classCount = logitsShape[2].toInt()
        if (anchors <= 0 || classCount <= 0) return emptyList()

        val paddedSize = maxOf(input.originalW, input.originalH).toFloat()
        val detections = mutableListOf<Detection>()
        for (anchor in 0 until anchors) {
            var bestScore = 0f
            for (classIndex in 0 until classCount) {
                val score = sigmoid(logits.getOrElse(anchor * classCount + classIndex) { -Float.MAX_VALUE })
                if (score > bestScore) bestScore = score
            }
            if (bestScore < confidenceThreshold) continue

            val boxOffset = anchor * 4
            val cx = boxes.getOrElse(boxOffset) { 0f } * paddedSize
            val cy = boxes.getOrElse(boxOffset + 1) { 0f } * paddedSize
            val w = boxes.getOrElse(boxOffset + 2) { 0f } * paddedSize
            val h = boxes.getOrElse(boxOffset + 3) { 0f } * paddedSize
            val left = (cx - w / 2f).coerceIn(0f, input.originalW.toFloat())
            val top = (cy - h / 2f).coerceIn(0f, input.originalH.toFloat())
            val right = (cx + w / 2f).coerceIn(0f, input.originalW.toFloat())
            val bottom = (cy + h / 2f).coerceIn(0f, input.originalH.toFloat())
            if (right - left < 4f || bottom - top < 4f) continue

            val expanded = expandRect(RectF(left, top, right, bottom), input.originalW, input.originalH, paddingFraction = OWL_PADDING_FRACTION)
            val boxArea = expanded.width * expanded.height
            val frameArea = input.originalW.toFloat() * input.originalH.toFloat()
            if (boxArea > frameArea * OWL_MAX_AREA_FRACTION) {
                println("[NSFW-iOS] decodeOwlOutput(): rejecting oversized bbox area=${(boxArea / frameArea * 100f).toInt()}% score=$bestScore raw=${(right-left).toInt()}x${(bottom-top).toInt()} expanded=${expanded.width.toInt()}x${expanded.height.toInt()}")
                continue
            }

            detections += Detection(
                rect = expanded,
                score = bestScore,
            )
        }
        return nms(detections, NMS_IOU_THRESHOLD)
    }

    private fun decodeDetectorOutput(
        outputData: FloatArray,
        outputShape: LongArray,
        input: DetectorInput,
        labels: Array<String>,
        blurLabels: Set<String>,
        confidenceThreshold: Float,
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        if (outputShape.size < 3 || outputData.isEmpty()) return detections

        val second = outputShape[1].toInt()
        val third = outputShape[2].toInt()
        val classCount = labels.size
        val channelsFirst = second == classCount + 4
        val channels = if (channelsFirst) second else third
        val anchors = if (channelsFirst) third else second
        if (channels < classCount + 4 || anchors <= 0) return detections

        fun value(channel: Int, anchor: Int): Float {
            val index = if (channelsFirst) channel * anchors + anchor else anchor * channels + channel
            return outputData.getOrElse(index) { 0f }
        }

        for (anchor in 0 until anchors) {
            var bestClass = -1
            var bestScore = 0f
            for (classIndex in 0 until classCount) {
                val score = value(4 + classIndex, anchor)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }
            if (bestClass < 0 || bestScore < confidenceThreshold) continue
            val label = labels[bestClass]
            if (label !in blurLabels) continue

            val cx = value(0, anchor)
            val cy = value(1, anchor)
            val w = value(2, anchor)
            val h = value(3, anchor)
            val left = ((cx - w / 2f - input.padX) / input.scale).coerceIn(0f, input.originalW.toFloat())
            val top = ((cy - h / 2f - input.padY) / input.scale).coerceIn(0f, input.originalH.toFloat())
            val right = ((cx + w / 2f - input.padX) / input.scale).coerceIn(0f, input.originalW.toFloat())
            val bottom = ((cy + h / 2f - input.padY) / input.scale).coerceIn(0f, input.originalH.toFloat())
            if (right - left < 2f || bottom - top < 2f) continue

            detections += Detection(
                rect = expandRect(RectF(left, top, right, bottom), input.originalW, input.originalH, paddingFraction = DETECTOR_PADDING_FRACTION),
                score = bestScore,
            )
        }
        return detections
    }

    private fun imageToMainRgb(image: ImagePixels): ByteArray {
        val out = ByteArray(MAIN_MODEL_SIZE * MAIN_MODEL_SIZE * 3)
        val scaleX = image.width.toFloat() / MAIN_MODEL_SIZE.toFloat()
        val scaleY = image.height.toFloat() / MAIN_MODEL_SIZE.toFloat()
        for (y in 0 until MAIN_MODEL_SIZE) {
            for (x in 0 until MAIN_MODEL_SIZE) {
                val sx = (x + 0.5f) * scaleX - 0.5f
                val sy = (y + 0.5f) * scaleY - 0.5f
                val rgb = sampleRgb(image, sx, sy, 0x000000)
                val index = (y * MAIN_MODEL_SIZE + x) * 3
                out[index] = ((rgb shr 16) and 0xFF).toByte()
                out[index + 1] = ((rgb shr 8) and 0xFF).toByte()
                out[index + 2] = (rgb and 0xFF).toByte()
            }
        }
        return out
    }

    private fun createAnalysisImage(source: ImagePixels): ImagePixels {
        val largestSide = maxOf(source.width, source.height)
        if (largestSide <= MAX_ANALYSIS_DIM) return source
        val scale = MAX_ANALYSIS_DIM.toFloat() / largestSide.toFloat()
        val targetW = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (source.height * scale).roundToInt().coerceAtLeast(1)
        return resizeImage(source, targetW, targetH)
    }

    private fun resizeImage(source: ImagePixels, targetW: Int, targetH: Int): ImagePixels {
        val out = ImagePixels(targetW, targetH, ByteArray(targetW * targetH * 4))
        val scaleX = source.width.toFloat() / targetW.toFloat()
        val scaleY = source.height.toFloat() / targetH.toFloat()
        for (y in 0 until targetH) {
            val sy = (y + 0.5f) * scaleY - 0.5f
            for (x in 0 until targetW) {
                val sx = (x + 0.5f) * scaleX - 0.5f
                val rgb = sampleRgb(source, sx, sy, 0x000000)
                setPixelRgb(out, x, y, rgb)
            }
        }
        return out
    }

    private fun scaleRect(rect: RectF, scaleX: Float, scaleY: Float, imageW: Int, imageH: Int): RectF {
        return RectF(
            (rect.left * scaleX).coerceIn(0f, imageW.toFloat()),
            (rect.top * scaleY).coerceIn(0f, imageH.toFloat()),
            (rect.right * scaleX).coerceIn(0f, imageW.toFloat()),
            (rect.bottom * scaleY).coerceIn(0f, imageH.toFloat()),
        )
    }

    private fun blurRegions(source: ImagePixels, regions: List<RectF>, mime: String?): ByteArray {
        val output = source.copy(pixels = source.pixels.copyOf())
        // Mask is at full image resolution to match the Python reference and
        // avoid uncontrolled feather expansion when upsampling a smaller mask
        // back to the source size.
        val maskW = source.width
        val maskH = source.height
        val mask = FloatArray(maskW * maskH)
        regions.forEach { region ->
            val left = region.left.roundToInt().coerceIn(0, maskW - 1)
            val top = region.top.roundToInt().coerceIn(0, maskH - 1)
            val right = region.right.roundToInt().coerceIn(left + 1, maskW)
            val bottom = region.bottom.roundToInt().coerceIn(top + 1, maskH)
            val width = right - left
            val height = bottom - top
            if (width < 4 || height < 4) return@forEach
            // Always draw the region as an ellipse; NEVER auto-promote to
            // full-frame just because the bbox happens to be large. The old
            // ">75% area → full frame" rule was misleading on close-up shots.
            fillEllipse(mask, maskW, maskH, left, top, right, bottom)
        }
        // Mask feather mirrors Python: kernel = max(min(h,w)/20, 21) → radius
        // ~min/40, ≥10. Larger feathers visibly grow the censored area.
        val feather = blurMaskGaussian(mask, maskW, maskH, (minOf(maskW, maskH) / 40).coerceAtLeast(10))
        val blurredWork = createDenseBlurredImage(source)
        // Upscale blur to source resolution before composite so the per-pixel
        // mask in [applyMaskedBlur] doesn't have to interpolate the blur
        // across mismatched dimensions (which softens edges and bleeds the
        // censor outside the detected ellipse).
        val blurred = if (blurredWork.width == source.width && blurredWork.height == source.height) {
            blurredWork
        } else {
            resizeImage(blurredWork, source.width, source.height)
        }
        applyMaskedBlur(source, blurred, feather, maskW, maskH, output)
        return encodeImage(output, mime)
    }

    /**
     * Build the dense, irreversible blur layer used to overlay the censored
     * regions. Mirrors the original Python pipeline:
     *   1. Downscale to [DENSE_PIXELATE_BASE]² then upscale back (severe
     *      pixelation low-pass).
     *   2. Apply [DENSE_BLUR_PASSES] separable Gaussian blurs with a large
     *      radius proportional to the working canvas (~workDim/16, ≥16 px).
     */
    private fun createDenseBlurredImage(source: ImagePixels): ImagePixels {
        val largestSide = maxOf(source.width, source.height)
        val workScale = minOf(1f, MAX_BLUR_WORK_DIM.toFloat() / largestSide.toFloat())
        val workW = (source.width * workScale).roundToInt().coerceAtLeast(1)
        val workH = (source.height * workScale).roundToInt().coerceAtLeast(1)
        val work = if (workW == source.width && workH == source.height) source else resizeImage(source, workW, workH)
        val tinyDim = DENSE_PIXELATE_BASE
        val tiny = resizeImage(work, tinyDim, tinyDim)
        var blurred = resizeImage(tiny, work.width, work.height)
        val workDim = min(work.width, work.height)
        val radius = max(workDim / DENSE_BLUR_RADIUS_DIVISOR, 16)
        repeat(DENSE_BLUR_PASSES) {
            blurred = gaussianBlurImage(blurred, radius)
        }
        return blurred
    }

    private fun fillEllipse(mask: FloatArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = ((right - left) / 2f).coerceAtLeast(1f)
        val ry = ((bottom - top) / 2f).coerceAtLeast(1f)
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            val ny = (y + 0.5f - cy) / ry
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) {
                val nx = (x + 0.5f - cx) / rx
                if (nx * nx + ny * ny <= 1f) mask[y * width + x] = 1f
            }
        }
    }

    private fun blurMaskGaussian(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (width <= 1 || height <= 1 || radius <= 0) return mask.copyOf()
        val sigma = radius / 2f
        val kernel = buildGaussianKernel(radius, sigma)
        val tmp = FloatArray(mask.size)
        val out = FloatArray(mask.size)
        gaussianPassHorizontalFloat(mask, tmp, width, height, kernel, radius)
        gaussianPassVerticalFloat(tmp, out, width, height, kernel, radius)
        val maxVal = out.maxOrNull() ?: 0f
        if (maxVal > 0f) for (i in out.indices) out[i] = (out[i] / maxVal).coerceIn(0f, 1f)
        return out
    }

    /**
     * True separable Gaussian blur on RGB pixels (1D kernel applied
     * horizontally then vertically). Replaces the previous box-blur
     * approximation which left visible mosaic artefacts at large radii.
     */
    private fun gaussianBlurImage(source: ImagePixels, radius: Int): ImagePixels {
        if (source.width <= 1 || source.height <= 1 || radius <= 0) {
            return source.copy(pixels = source.pixels.copyOf())
        }
        val sigma = radius / 2f
        val kernel = buildGaussianKernel(radius, sigma)
        val pixelCount = source.width * source.height
        val src = IntArray(pixelCount) { idx ->
            val base = idx * 4
            ((source.pixels[base].toInt() and 0xFF) shl 16) or
                    ((source.pixels[base + 1].toInt() and 0xFF) shl 8) or
                    (source.pixels[base + 2].toInt() and 0xFF)
        }
        val tmp = IntArray(pixelCount)
        val out = IntArray(pixelCount)
        gaussianPassHorizontalRgb(src, tmp, source.width, source.height, kernel, radius)
        gaussianPassVerticalRgb(tmp, out, source.width, source.height, kernel, radius)
        val pixels = ByteArray(pixelCount * 4)
        for (i in out.indices) {
            val base = i * 4
            val rgb = out[i]
            pixels[base] = ((rgb shr 16) and 0xFF).toByte()
            pixels[base + 1] = ((rgb shr 8) and 0xFF).toByte()
            pixels[base + 2] = (rgb and 0xFF).toByte()
            pixels[base + 3] = 0xFF.toByte()
        }
        return ImagePixels(source.width, source.height, pixels)
    }

    private fun buildGaussianKernel(radius: Int, sigma: Float): FloatArray {
        val size = radius * 2 + 1
        val kernel = FloatArray(size)
        val twoSigmaSq = 2f * sigma * sigma
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toFloat()
            val v = exp(-(x * x) / twoSigmaSq)
            kernel[i] = v
            sum += v
        }
        for (i in 0 until size) kernel[i] /= sum
        return kernel
    }

    private fun gaussianPassHorizontalRgb(src: IntArray, dst: IntArray, width: Int, height: Int, kernel: FloatArray, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    val color = src[row + sx]
                    val w = kernel[k + radius]
                    r += ((color shr 16) and 0xFF) * w
                    g += ((color shr 8) and 0xFF) * w
                    b += (color and 0xFF) * w
                }
                dst[row + x] = (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun gaussianPassVerticalRgb(src: IntArray, dst: IntArray, width: Int, height: Int, kernel: FloatArray, radius: Int) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    val color = src[sy * width + x]
                    val w = kernel[k + radius]
                    r += ((color shr 16) and 0xFF) * w
                    g += ((color shr 8) and 0xFF) * w
                    b += (color and 0xFF) * w
                }
                dst[y * width + x] = (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun gaussianPassHorizontalFloat(src: FloatArray, dst: FloatArray, width: Int, height: Int, kernel: FloatArray, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    sum += src[row + (x + k).coerceIn(0, width - 1)] * kernel[k + radius]
                }
                dst[row + x] = sum
            }
        }
    }

    private fun gaussianPassVerticalFloat(src: FloatArray, dst: FloatArray, width: Int, height: Int, kernel: FloatArray, radius: Int) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0f
                for (k in -radius..radius) {
                    sum += src[(y + k).coerceIn(0, height - 1) * width + x] * kernel[k + radius]
                }
                dst[y * width + x] = sum
            }
        }
    }

    private fun applyMaskedBlur(
        source: ImagePixels,
        blurred: ImagePixels,
        mask: FloatArray,
        maskW: Int,
        maskH: Int,
        output: ImagePixels,
    ) {
        val scaleX = maskW.toFloat() / source.width.toFloat()
        val scaleY = maskH.toFloat() / source.height.toFloat()
        val blurredScaleX = blurred.width.toFloat() / source.width.toFloat()
        val blurredScaleY = blurred.height.toFloat() / source.height.toFloat()
        for (y in 0 until source.height) {
            val my = (y * scaleY).roundToInt().coerceIn(0, maskH - 1)
            val by = (y * blurredScaleY).roundToInt().coerceIn(0, blurred.height - 1)
            for (x in 0 until source.width) {
                val mx = (x * scaleX).roundToInt().coerceIn(0, maskW - 1)
                val alpha = mask[my * maskW + mx]
                if (alpha <= 0f) continue
                val bx = (x * blurredScaleX).roundToInt().coerceIn(0, blurred.width - 1)
                val base = pixelRgb(source, x, y)
                val over = pixelRgb(blurred, bx, by)
                setPixelRgb(output, x, y, blendRgb(base, over, alpha))
            }
        }
    }

    private fun blendRgb(base: Int, overlay: Int, alpha: Float): Int {
        val inv = 1f - alpha
        val r = (((base shr 16) and 0xFF) * inv + ((overlay shr 16) and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        val g = (((base shr 8) and 0xFF) * inv + ((overlay shr 8) and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        val b = ((base and 0xFF) * inv + (overlay and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun sampleRgb(image: ImagePixels, x: Float, y: Float, fallback: Int): Int {
        if (x < 0f || y < 0f || x > (image.width - 1).toFloat() || y > (image.height - 1).toFloat()) return fallback
        val x0 = floor(x).toInt().coerceIn(0, image.width - 1)
        val y0 = floor(y).toInt().coerceIn(0, image.height - 1)
        val x1 = (x0 + 1).coerceAtMost(image.width - 1)
        val y1 = (y0 + 1).coerceAtMost(image.height - 1)
        val wx = x - x0
        val wy = y - y0

        fun channel(c: Int): Int {
            val c00 = pixelChannel(image, x0, y0, c)
            val c10 = pixelChannel(image, x1, y0, c)
            val c01 = pixelChannel(image, x0, y1, c)
            val c11 = pixelChannel(image, x1, y1, c)
            val top = c00 * (1f - wx) + c10 * wx
            val bottom = c01 * (1f - wx) + c11 * wx
            return (top * (1f - wy) + bottom * wy).roundToInt().coerceIn(0, 255)
        }

        return (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    private fun pixelRgb(image: ImagePixels, x: Int, y: Int): Int {
        val base = (y.coerceIn(0, image.height - 1) * image.width + x.coerceIn(0, image.width - 1)) * 4
        return ((image.pixels[base].toInt() and 0xFF) shl 16) or
                ((image.pixels[base + 1].toInt() and 0xFF) shl 8) or
                (image.pixels[base + 2].toInt() and 0xFF)
    }

    private fun setPixelRgb(image: ImagePixels, x: Int, y: Int, rgb: Int) {
        val base = (y.coerceIn(0, image.height - 1) * image.width + x.coerceIn(0, image.width - 1)) * 4
        image.pixels[base] = ((rgb shr 16) and 0xFF).toByte()
        image.pixels[base + 1] = ((rgb shr 8) and 0xFF).toByte()
        image.pixels[base + 2] = (rgb and 0xFF).toByte()
        image.pixels[base + 3] = 0xFF.toByte()
    }

    private fun pixelChannel(image: ImagePixels, x: Int, y: Int, channel: Int): Int {
        return image.pixels[(y * image.width + x) * 4 + channel].toInt() and 0xFF
    }

    private fun expandRect(rect: RectF, imageW: Int, imageH: Int, paddingFraction: Float): RectF {
        val padX = (rect.width * paddingFraction).coerceAtLeast(4f)
        val padY = (rect.height * paddingFraction).coerceAtLeast(4f)
        return RectF(
            (rect.left - padX).coerceAtLeast(0f),
            (rect.top - padY).coerceAtLeast(0f),
            (rect.right + padX).coerceAtMost(imageW.toFloat()),
            (rect.bottom + padY).coerceAtMost(imageH.toFloat()),
        )
    }

    private fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val kept = mutableListOf<Detection>()
        detections.sortedByDescending { it.score }.forEach { detection ->
            if (kept.none { iou(it.rect, detection.rect) > iouThreshold }) kept += detection
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return 0f
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))

    fun close() {
        val toClose = synchronized(sessionCacheLock) {
            val snapshot = sessionCache.values.toList()
            sessionCache.clear()
            snapshot
        }
        toClose.forEach { cached ->
            runCatching { bridge.closeSession(cached.handle) }
        }
    }

    private fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    private data class CachedSession(
        val handle: Long,
        val modelPath: String,
        var lastUsedAt: Long,
    )

    private data class SlidingWindowResult(
        val fullFrame: Boolean,
        val regions: List<RectF>,
        val maxScore: Float,
    )

    private enum class PaddingMode { TOP_LEFT_SQUARE, CENTER_LETTERBOX }

    private data class DetectorInput(
        val data: FloatArray,
        val originalW: Int,
        val originalH: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    private data class Detection(
        val rect: RectF,
        val score: Float,
        val source: String = "",
    )

    private data class RectF(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    data class ImagePixels(
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(pinned.addressOf(0), this.size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = this.length.toInt()
    val out = ByteArray(len)
    if (len > 0) out.usePinned { memcpy(it.addressOf(0), this.bytes, this.length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun decodeImage(bytes: ByteArray): NsfwCensorEngine.ImagePixels? {
    if (bytes.isEmpty()) return null
    val ui = UIImage.imageWithData(bytes.toNSData()) ?: return null
    val cg = ui.CGImage ?: return null
    val w = CGImageGetWidth(cg).toInt()
    val h = CGImageGetHeight(cg).toInt()
    if (w <= 0 || h <= 0) return null
    val rowBytes = w * 4
    val pixels = ByteArray(rowBytes * h)
    val cs = CGColorSpaceCreateDeviceRGB() ?: return null
    val ok = pixels.usePinned { pinned ->
        val ctx = CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = w.convert(),
            height = h.convert(),
            bitsPerComponent = 8.convert(),
            bytesPerRow = rowBytes.convert(),
            space = cs,
            bitmapInfo = (CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big).convert(),
        ) ?: return@usePinned false
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cg)
        true
    }
    if (!ok) return null
    return NsfwCensorEngine.ImagePixels(w, h, pixels)
}

@OptIn(ExperimentalForeignApi::class)
private fun encodeImage(image: NsfwCensorEngine.ImagePixels, mime: String?): ByteArray {
    val rowBytes = image.width * 4
    val cs = CGColorSpaceCreateDeviceRGB() ?: error("encodeImage: no colorspace")
    val ui = image.pixels.usePinned { pinned ->
        val ctx = CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = image.width.convert(),
            height = image.height.convert(),
            bitsPerComponent = 8.convert(),
            bytesPerRow = rowBytes.convert(),
            space = cs,
            bitmapInfo = (CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big).convert(),
        ) ?: error("encodeImage: CGBitmapContextCreate failed")
        val cg = CGBitmapContextCreateImage(ctx) ?: error("encodeImage: snapshot failed")
        UIImage.imageWithCGImage(cg)
    }
    val data = if (mime.equals("image/png", ignoreCase = true)) {
        UIImagePNGRepresentation(ui)
    } else {
        UIImageJPEGRepresentation(ui, 0.88)
    } ?: error("encodeImage: image encode failed")
    return data.toByteArray()
}
