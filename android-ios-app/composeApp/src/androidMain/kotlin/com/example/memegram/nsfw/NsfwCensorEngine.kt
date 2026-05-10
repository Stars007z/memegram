package com.example.memegram.nsfw

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.platform.Fp16Conversions
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class NsfwCensorEngine private constructor(
    private val env: OrtEnvironment,
    private val mainModelFile: File,
    private val nudeNetModelFile: File,
    private val animeCensorModelFile: File,
    private val owlSwastikaModelFile: File,
) : AutoCloseable {

    companion object {
        private const val TAG = "NSFW"
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
        private val MAIN_INPUT_SHAPE = longArrayOf(1, MAIN_MODEL_SIZE.toLong(), MAIN_MODEL_SIZE.toLong(), 3)

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
            mainModelFile: File,
            nudeNetModelFile: File,
            animeCensorModelFile: File,
            owlSwastikaModelFile: File,
        ): NsfwCensorEngine {
            require(mainModelFile.exists()) { "NSFW main model not found: ${mainModelFile.absolutePath}" }
            require(nudeNetModelFile.exists()) { "NudeNet model not found: ${nudeNetModelFile.absolutePath}" }
            require(animeCensorModelFile.exists()) { "Anime censor model not found: ${animeCensorModelFile.absolutePath}" }
            require(owlSwastikaModelFile.exists()) { "OWL swastika model not found: ${owlSwastikaModelFile.absolutePath}" }

            val env = OrtEnvironment.getEnvironment()
            return NsfwCensorEngine(
                env = env,
                mainModelFile = mainModelFile,
                nudeNetModelFile = nudeNetModelFile,
                animeCensorModelFile = animeCensorModelFile,
                owlSwastikaModelFile = owlSwastikaModelFile,
            )
        }

        private fun createSession(
            env: OrtEnvironment,
            modelFile: File,
            preferNnapi: Boolean,
        ): OrtSession {
            if (preferNnapi) {
                val nnapiOptions = createSessionOptions()
                try {
                    nnapiOptions.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                    return env.createSession(modelFile.absolutePath, nnapiOptions)
                } catch (e: Throwable) {
                    Log.w(TAG, "createSession(): NNAPI unavailable for ${modelFile.name}: ${e.message}")
                } finally {
                    runCatching { nnapiOptions.close() }
                }
            }

            val cpuOptions = createSessionOptions()
            return try {
                env.createSession(modelFile.absolutePath, cpuOptions)
            } finally {
                runCatching { cpuOptions.close() }
            }
        }

        private fun createSessionOptions() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(2))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
    }

    private val sessionCache = ConcurrentHashMap<String, CachedSession>()

    suspend fun censorImageIfNeeded(
        imageBytes: ByteArray,
        mime: String?,
    ): NsfwCensorResult = withContext(Dispatchers.Default) {
        val analysis = decodeAnalysisBitmap(imageBytes)
            ?: error("Failed to decode image for NSFW classification")

        try {
            val mainScores = classifyMainScores(analysis.bitmap)
            val prediction = mainScores.indices.maxByOrNull { mainScores[it] } ?: MAIN_NUDITY_LABEL_INDEX
            Log.d(TAG, "censorImageIfNeeded(): mainPrediction=$prediction scores=${mainScores.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }}")

            val detections = detectRegions(analysis.bitmap).map { detection ->
                detection.copy(
                    rect = scaleRect(
                        rect = detection.rect,
                        scaleX = analysis.scaleX,
                        scaleY = analysis.scaleY,
                        imageW = analysis.originalWidth,
                        imageH = analysis.originalHeight,
                    ),
                )
            }.toMutableList()

            val slidingTriggers = SLIDING_TRIGGER_CLASSES.filter { (idx, _) -> mainScores.getOrElse(idx) { 0f } >= SLIDING_TRIGGER_THRESHOLD }
            if (slidingTriggers.isNotEmpty()) {
                val targetIndices = slidingTriggers.map { it.first }
                val sliding = runSlidingWindow(
                    bitmap = analysis.bitmap,
                    targetClassIndices = targetIndices,
                )
                if (sliding.fullFrame) {
                    Log.d(TAG, "censorImageIfNeeded(): sliding window escalated to full-frame censor (target=$targetIndices)")
                    detections.clear()
                    detections += Detection(
                        rect = RectF(0f, 0f, analysis.originalWidth.toFloat(), analysis.originalHeight.toFloat()),
                        score = sliding.maxScore,
                        source = "sliding-full",
                    )
                } else if (sliding.regions.isNotEmpty()) {
                    Log.d(TAG, "censorImageIfNeeded(): sliding window adds ${sliding.regions.size} region(s) (target=$targetIndices)")
                    sliding.regions.forEach { rect ->
                        detections += Detection(
                            rect = scaleRect(
                                rect = rect,
                                scaleX = analysis.scaleX,
                                scaleY = analysis.scaleY,
                                imageW = analysis.originalWidth,
                                imageH = analysis.originalHeight,
                            ),
                            score = sliding.maxScore,
                            source = "sliding",
                        )
                    }
                }
            }

            if (detections.isNotEmpty()) {
                val merged = nms(detections, NMS_IOU_THRESHOLD)
                Log.d(TAG, "censorImageIfNeeded(): blurring ${merged.size} region(s), ${formatDetectionAreas(merged, analysis.originalWidth, analysis.originalHeight)}")
                merged.forEachIndexed { i, d ->
                    val pctW = ((d.rect.width() / analysis.originalWidth.toFloat()) * 100f).toInt()
                    val pctH = ((d.rect.height() / analysis.originalHeight.toFloat()) * 100f).toInt()
                    val scoreStr = "%.2f".format(d.score)
                    Log.d(TAG, "  detect[$i] src=${d.source} score=$scoreStr rect=L=${d.rect.left.toInt()} T=${d.rect.top.toInt()} R=${d.rect.right.toInt()} B=${d.rect.bottom.toInt()} (${pctW}%x${pctH}%)")
                }

                val nudityScore = mainScores.getOrElse(MAIN_NUDITY_LABEL_INDEX) { 0f }
                val hasNudityRegion = merged.any { it.source == "nudenet" || it.source == "anime" }
                if (nudityScore >= NUDITY_FALLBACK_THRESHOLD && !hasNudityRegion) {
                    Log.d(TAG, "censorImageIfNeeded(): nudity fallback triggered (score=$nudityScore >= $NUDITY_FALLBACK_THRESHOLD, no nudity-specific region among ${merged.size} detection(s)), full-frame blur")
                    val fullBitmap = decodeFullBitmap(imageBytes)
                        ?: error("Failed to decode original image for NSFW blur")
                    val fullRect = RectF(0f, 0f, fullBitmap.width.toFloat(), fullBitmap.height.toFloat())
                    return@withContext NsfwCensorResult(
                        bytes = try {
                            blurRegions(fullBitmap, listOf(fullRect), mime)
                        } finally {
                            fullBitmap.recycle()
                        },
                        processed = true,
                    )
                }

                val fullBitmap = decodeFullBitmap(imageBytes)
                    ?: error("Failed to decode original image for NSFW blur")
                return@withContext NsfwCensorResult(
                    bytes = try {
                        blurRegions(fullBitmap, merged.map { it.rect }, mime)
                    } finally {
                        fullBitmap.recycle()
                    },
                    processed = true,
                )
            }

            val nudityScore = mainScores.getOrElse(MAIN_NUDITY_LABEL_INDEX) { 0f }
            if (nudityScore >= NUDITY_FALLBACK_THRESHOLD) {
                Log.d(TAG, "censorImageIfNeeded(): nudity fallback triggered (score=$nudityScore >= $NUDITY_FALLBACK_THRESHOLD, no detections), full-frame blur")
                val fullBitmap = decodeFullBitmap(imageBytes)
                    ?: error("Failed to decode original image for NSFW blur")
                val fullRect = RectF(0f, 0f, fullBitmap.width.toFloat(), fullBitmap.height.toFloat())
                return@withContext NsfwCensorResult(
                    bytes = try {
                        blurRegions(fullBitmap, listOf(fullRect), mime)
                    } finally {
                        fullBitmap.recycle()
                    },
                    processed = true,
                )
            }

            NsfwCensorResult(imageBytes, processed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "censorImageIfNeeded(): inference failed: ${e::class.simpleName}: ${e.message}", e)
            NsfwCensorResult(imageBytes, processed = false)
        } finally {
            analysis.bitmap.recycle()
            maybeEvictIdleSessions()
        }
    }

    private fun detectRegions(bitmap: Bitmap): List<Detection> {
        val nudeNet = withSession(nudeNetModelFile, preferNnapi = false) { session -> runDetector(
            session = session,
            bitmap = bitmap,
            modelSize = NUDENET_SIZE,
            confidenceThreshold = NUDENET_CONFIDENCE,
            labels = NUDENET_LABELS,
            blurLabels = NUDENET_BLUR_LABELS,
            paddingMode = PaddingMode.TOP_LEFT_SQUARE,
        ) }.map { it.copy(source = "nudenet") }
        val anime = withSession(animeCensorModelFile, preferNnapi = false) { session -> runDetector(
            session = session,
            bitmap = bitmap,
            modelSize = ANIME_SIZE,
            confidenceThreshold = ANIME_CONFIDENCE,
            labels = ANIME_LABELS,
            blurLabels = ANIME_BLUR_LABELS,
            paddingMode = PaddingMode.CENTER_LETTERBOX,
        ) }.map { it.copy(source = "anime") }
        val swastika = withSession(owlSwastikaModelFile, preferNnapi = false) { session ->
            runOwlSwastikaDetector(session, bitmap)
        }.map { it.copy(source = "owl") }
        Log.d(TAG, "detectRegions(): nudenet=${nudeNet.size} anime=${anime.size} owl=${swastika.size}")
        return nms(nudeNet + anime + swastika, NMS_IOU_THRESHOLD)
    }

    private fun classifyMainScores(bitmap: Bitmap): FloatArray {
        return runMainClassifier(bitmap)
    }

    private fun runMainClassifier(bitmap: Bitmap): FloatArray {
        val inputBuffer = bitmapToMainRgbBuffer(bitmap)
        OnnxTensor.createTensor(env, inputBuffer, MAIN_INPUT_SHAPE, OnnxJavaType.UINT8).use { inputTensor ->
            return withSession(mainModelFile, preferNnapi = false) { session ->
                val inputName = session.inputNames.firstOrNull { it == MAIN_INPUT_NAME }
                    ?: session.inputNames.first()
                session.run(mapOf(inputName to inputTensor)).use { outputs ->
                    val output = outputs.get(MAIN_OUTPUT_NAME).orElse(outputs[0]) as OnnxTensor
                    readMainOutputScores(output)
                }
            }
        }
    }

    private fun <T> withSession(
        modelFile: File,
        preferNnapi: Boolean,
        block: (OrtSession) -> T,
    ): T {
        val key = modelFile.absolutePath
        val cached = sessionCache.compute(key) { _, existing ->
            if (existing != null) {
                existing.lastUsedAt = System.currentTimeMillis()
                existing
            } else {
                val newSession = createSession(env, modelFile, preferNnapi)
                CachedSession(
                    session = newSession,
                    modelName = modelFile.name,
                    lastUsedAt = System.currentTimeMillis(),
                )
            }
        }!!
        return try {
            block(cached.session)
        } finally {
            cached.lastUsedAt = System.currentTimeMillis()
        }
    }

    private fun maybeEvictIdleSessions() {
        val now = System.currentTimeMillis()
        val keys = sessionCache.keys.toList()
        var evicted = false
        for (key in keys) {
            val cached = sessionCache[key] ?: continue
            if (now - cached.lastUsedAt >= SESSION_IDLE_TIMEOUT_MS) {
                if (sessionCache.remove(key, cached)) {
                    runCatching { cached.session.close() }
                    Log.d(TAG, "maybeEvictIdleSessions(): closed idle session ${cached.modelName}")
                    evicted = true
                }
            }
        }
        if (evicted) {
            System.gc()
            val nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            Log.d(TAG, "maybeEvictIdleSessions(): nativeHeap=${nativeHeapMB}MB after eviction")
        }
    }

    private fun runSlidingWindow(
        bitmap: Bitmap,
        targetClassIndices: List<Int>,
    ): SlidingWindowResult {
        val w = bitmap.width
        val h = bitmap.height
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
                val crop = Bitmap.createBitmap(bitmap, x, y, winW, winH)
                val scores = try {
                    runMainClassifier(crop)
                } finally {
                    crop.recycle()
                }
                val cropScore = targetClassIndices.maxOf { scores.getOrElse(it) { 0f } }
                if (cropScore > maxScore) maxScore = cropScore
                if (cropScore >= SLIDING_PER_WINDOW_THRESHOLD) {
                    triggered++
                    regions += RectF(x.toFloat(), y.toFloat(), (x + winW).toFloat(), (y + winH).toFloat())
                }
            }
        }

        val ratio = triggered.toFloat() / totalScans.toFloat()
        Log.d(TAG, "runSlidingWindow(): triggered=$triggered/$totalScans ratio=${"%.2f".format(ratio)} maxScore=${"%.3f".format(maxScore)}")

        if (ratio >= SLIDING_FULL_BLUR_RATIO && maxScore >= SLIDING_FULL_BLUR_MIN_SCORE) {
            return SlidingWindowResult(fullFrame = true, regions = emptyList(), maxScore = maxScore)
        }
        return SlidingWindowResult(fullFrame = false, regions = regions, maxScore = maxScore)
    }

    private fun runDetector(
        session: OrtSession,
        bitmap: Bitmap,
        modelSize: Int,
        confidenceThreshold: Float,
        labels: Array<String>,
        blurLabels: Set<String>,
        paddingMode: PaddingMode,
    ): List<Detection> {
        val input = prepareDetectorInput(bitmap, modelSize, paddingMode)
        val shape = longArrayOf(1, 3, modelSize.toLong(), modelSize.toLong())
        OnnxTensor.createTensor(env, input.buffer, shape).use { inputTensor ->
            val inputName = session.inputNames.firstOrNull { it == DETECTOR_INPUT_NAME }
                ?: session.inputNames.first()
            session.run(mapOf(inputName to inputTensor)).use { outputs ->
                val output = outputs.get(DETECTOR_OUTPUT_NAME).orElse(outputs[0]) as OnnxTensor
                return decodeDetectorOutput(
                    tensor = output,
                    input = input,
                    labels = labels,
                    blurLabels = blurLabels,
                    confidenceThreshold = confidenceThreshold,
                )
            }
        }
    }

    private fun prepareDetectorInput(
        bitmap: Bitmap,
        modelSize: Int,
        paddingMode: PaddingMode,
    ): DetectorInput {
        val originalW = bitmap.width
        val originalH = bitmap.height
        val scale: Float
        val padX: Float
        val padY: Float
        val prepared: Bitmap

        when (paddingMode) {
            PaddingMode.TOP_LEFT_SQUARE -> {
                val maxSize = maxOf(originalW, originalH)
                scale = modelSize.toFloat() / maxSize.toFloat()
                padX = 0f
                padY = 0f
                prepared = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
                Canvas(prepared).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(
                        bitmap,
                        null,
                        RectF(0f, 0f, originalW * scale, originalH * scale),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                }
            }

            PaddingMode.CENTER_LETTERBOX -> {
                scale = minOf(
                    modelSize.toFloat() / originalW.toFloat(),
                    modelSize.toFloat() / originalH.toFloat(),
                )
                val scaledW = (originalW * scale).toInt().coerceIn(1, modelSize)
                val scaledH = (originalH * scale).toInt().coerceIn(1, modelSize)
                padX = (modelSize - scaledW) / 2f
                padY = (modelSize - scaledH) / 2f
                prepared = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
                Canvas(prepared).apply {
                    drawColor(Color.rgb(114, 114, 114))
                    drawBitmap(
                        bitmap,
                        null,
                        RectF(padX, padY, padX + scaledW, padY + scaledH),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                }
            }
        }

        val pixels = IntArray(modelSize * modelSize)
        prepared.getPixels(pixels, 0, modelSize, 0, 0, modelSize, modelSize)
        prepared.recycle()

        val buffer = ByteBuffer
            .allocateDirect(3 * modelSize * modelSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (channel in 0 until 3) {
            pixels.forEach { color ->
                val value = when (channel) {
                    0 -> (color shr 16) and 0xFF
                    1 -> (color shr 8) and 0xFF
                    else -> color and 0xFF
                }
                buffer.put(value / 255f)
            }
        }
        buffer.rewind()

        return DetectorInput(
            buffer = buffer,
            originalW = originalW,
            originalH = originalH,
            scale = scale,
            padX = padX,
            padY = padY,
        )
    }

    private fun runOwlSwastikaDetector(session: OrtSession, bitmap: Bitmap): List<Detection> {
        Log.d(TAG, "runOwlSwastikaDetector(): conf=$OWL_CONFIDENCE maxAreaFrac=$OWL_MAX_AREA_FRACTION (build-tag=cap-v3)")
        val input = prepareOwlInput(bitmap)
        val shape = longArrayOf(1, 3, OWL_SIZE.toLong(), OWL_SIZE.toLong())
        OnnxTensor.createTensor(env, input.buffer, shape).use { inputTensor ->
            val inputName = session.inputNames.firstOrNull { it == OWL_INPUT_NAME }
                ?: session.inputNames.first()
            session.run(mapOf(inputName to inputTensor)).use { outputs ->
                val logits = outputs.get(OWL_LOGITS_OUTPUT_NAME).orElse(outputs[0]) as OnnxTensor
                val boxes = outputs.get(OWL_BOXES_OUTPUT_NAME).orElse(outputs[2]) as OnnxTensor
                return decodeOwlOutput(
                    logitsTensor = logits,
                    boxesTensor = boxes,
                    input = input,
                    confidenceThreshold = OWL_CONFIDENCE,
                )
            }
        }
    }

    private fun prepareOwlInput(bitmap: Bitmap): DetectorInput {
        val originalW = bitmap.width
        val originalH = bitmap.height
        val paddedSize = maxOf(originalW, originalH)
        val scale = OWL_SIZE.toFloat() / paddedSize.toFloat()
        val prepared = Bitmap.createBitmap(OWL_SIZE, OWL_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(prepared).apply {
            drawColor(Color.rgb(128, 128, 128))
            drawBitmap(
                bitmap,
                null,
                RectF(0f, 0f, originalW * scale, originalH * scale),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }

        val pixels = IntArray(OWL_SIZE * OWL_SIZE)
        prepared.getPixels(pixels, 0, OWL_SIZE, 0, 0, OWL_SIZE, OWL_SIZE)
        prepared.recycle()

        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val buffer = ByteBuffer
            .allocateDirect(3 * OWL_SIZE * OWL_SIZE * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (channel in 0 until 3) {
            pixels.forEach { color ->
                val value = when (channel) {
                    0 -> (color shr 16) and 0xFF
                    1 -> (color shr 8) and 0xFF
                    else -> color and 0xFF
                } / 255f
                buffer.put((value - mean[channel]) / std[channel])
            }
        }
        buffer.rewind()

        return DetectorInput(
            buffer = buffer,
            originalW = originalW,
            originalH = originalH,
            scale = scale,
            padX = 0f,
            padY = 0f,
        )
    }

    private fun decodeOwlOutput(
        logitsTensor: OnnxTensor,
        boxesTensor: OnnxTensor,
        input: DetectorInput,
        confidenceThreshold: Float,
    ): List<Detection> {
        val logitsBuffer = logitsTensor.floatBuffer
        val logits = FloatArray(logitsBuffer.remaining())
        logitsBuffer.get(logits)
        val boxesBuffer = boxesTensor.floatBuffer
        val boxes = FloatArray(boxesBuffer.remaining())
        boxesBuffer.get(boxes)

        val shape = logitsTensor.info.shape
        if (shape.size < 3 || boxes.isEmpty()) return emptyList()
        val anchors = shape[1].toInt()
        val classCount = shape[2].toInt()
        if (anchors <= 0 || classCount <= 0) return emptyList()

        val paddedSize = maxOf(input.originalW, input.originalH).toFloat()
        val detections = mutableListOf<Detection>()
        for (anchor in 0 until anchors) {
            var bestScore = 0f
            for (classIndex in 0 until classCount) {
                val logit = logits.getOrElse(anchor * classCount + classIndex) { -Float.MAX_VALUE }
                val score = sigmoid(logit)
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
            val boxArea = expanded.width() * expanded.height()
            val frameArea = input.originalW.toFloat() * input.originalH.toFloat()
            if (boxArea > frameArea * OWL_MAX_AREA_FRACTION) {
                Log.d(TAG, "decodeOwlOutput(): rejecting oversized bbox area=${(boxArea / frameArea * 100f).toInt()}% score=$bestScore raw=${(right-left).toInt()}x${(bottom-top).toInt()} expanded=${expanded.width().toInt()}x${expanded.height().toInt()}")
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
        tensor: OnnxTensor,
        input: DetectorInput,
        labels: Array<String>,
        blurLabels: Set<String>,
        confidenceThreshold: Float,
    ): List<Detection> {
        val shape = tensor.info.shape
        val rawBuffer = tensor.floatBuffer
        val raw = FloatArray(rawBuffer.remaining())
        rawBuffer.get(raw)

        val detections = mutableListOf<Detection>()
        if (shape.size < 3) return detections

        val second = shape[1].toInt()
        val third = shape[2].toInt()
        val classCount = labels.size

        val channelsFirst = second == classCount + 4
        val channels = if (channelsFirst) second else third
        val anchors = if (channelsFirst) third else second
        if (channels < classCount + 4 || anchors <= 0) return detections

        fun value(channel: Int, anchor: Int): Float {
            return if (channelsFirst) raw[channel * anchors + anchor]
            else raw[anchor * channels + channel]
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
                rect = expandRect(
                    RectF(left, top, right, bottom),
                    input.originalW,
                    input.originalH,
                    paddingFraction = DETECTOR_PADDING_FRACTION,
                ),
                score = bestScore,
            )
        }
        return detections
    }

    private fun bitmapToMainRgbBuffer(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createBitmap(MAIN_MODEL_SIZE, MAIN_MODEL_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(resized).drawBitmap(
            bitmap,
            null,
            RectF(0f, 0f, MAIN_MODEL_SIZE.toFloat(), MAIN_MODEL_SIZE.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val pixels = IntArray(MAIN_MODEL_SIZE * MAIN_MODEL_SIZE)
        resized.getPixels(pixels, 0, MAIN_MODEL_SIZE, 0, 0, MAIN_MODEL_SIZE, MAIN_MODEL_SIZE)
        resized.recycle()

        val buffer = ByteBuffer
            .allocateDirect(MAIN_MODEL_SIZE * MAIN_MODEL_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        pixels.forEach { color ->
            buffer.put(((color shr 16) and 0xFF).toByte())
            buffer.put(((color shr 8) and 0xFF).toByte())
            buffer.put((color and 0xFF).toByte())
        }
        buffer.rewind()
        return buffer
    }

    private fun readMainOutputScores(tensor: OnnxTensor): FloatArray {
        runCatching {
            val buffer = tensor.floatBuffer
            val scores = FloatArray(buffer.remaining())
            buffer.get(scores)
            if (scores.isNotEmpty()) return scores.take(6).toFloatArray()
        }

        val halfBuffer = tensor.shortBuffer
        val scores = FloatArray(halfBuffer.remaining()) {
            Fp16Conversions.fp16ToFloat(halfBuffer.get())
        }
        return scores.take(6).toFloatArray()
    }

    private fun decodeAnalysisBitmap(bytes: ByteArray): AnalysisBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val originalW = bounds.outWidth
        val originalH = bounds.outHeight
        if (originalW <= 0 || originalH <= 0) return null

        val sampleSize = calculateSampleSize(originalW, originalH, MAX_ANALYSIS_DIM)
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val largestSide = maxOf(decoded.width, decoded.height)
        val bitmap = if (largestSide > MAX_ANALYSIS_DIM) {
            val scale = MAX_ANALYSIS_DIM.toFloat() / largestSide.toFloat()
            val targetW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also { decoded.recycle() }
        } else {
            decoded
        }

        return AnalysisBitmap(
            bitmap = bitmap,
            originalWidth = originalW,
            originalHeight = originalH,
            scaleX = originalW.toFloat() / bitmap.width.toFloat(),
            scaleY = originalH.toFloat() / bitmap.height.toFloat(),
        )
    }

    private fun decodeFullBitmap(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        },
    )

    private fun calculateSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sampleSize = 1
        while (maxOf(width / sampleSize, height / sampleSize) > maxDim * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleRect(rect: RectF, scaleX: Float, scaleY: Float, imageW: Int, imageH: Int): RectF {
        return RectF(
            (rect.left * scaleX).coerceIn(0f, imageW.toFloat()),
            (rect.top * scaleY).coerceIn(0f, imageH.toFloat()),
            (rect.right * scaleX).coerceIn(0f, imageW.toFloat()),
            (rect.bottom * scaleY).coerceIn(0f, imageH.toFloat()),
        )
    }

    private fun formatDetectionAreas(detections: List<Detection>, imageW: Int, imageH: Int): String {
        val fullArea = imageW.toFloat() * imageH.toFloat()
        if (fullArea <= 0f) return "areas=[]"
        val areas = detections
            .map { ((it.rect.width() * it.rect.height() / fullArea) * 100f).roundToInt() }
            .sortedDescending()
            .joinToString(prefix = "areas=[", postfix = "]%")
        return areas
    }

    private fun blurRegions(source: Bitmap, regions: List<RectF>, mime: String?): ByteArray {
        val output = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true)
        Log.d(TAG, "blurRegions(): source=${source.width}x${source.height}, regions=${regions.size}")
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
            fillEllipse(mask, maskW, maskH, left, top, right, bottom)
        }

        val featherRadius = (minOf(maskW, maskH) / 20 / 2).coerceAtLeast(10)
        Log.d(TAG, "blurRegions(): featherRadius=$featherRadius")
        val feather = blurMaskGaussian(mask, maskW, maskH, featherRadius)

        var maskedPixels = 0
        var partialPixels = 0
        for (v in feather) {
            if (v >= 0.95f) maskedPixels++
            else if (v >= 0.05f) partialPixels++
        }
        val maskedPct = maskedPixels * 100 / feather.size
        val partialPct = partialPixels * 100 / feather.size
        Log.d(TAG, "blurRegions(): mask coverage opaque=$maskedPct% partial=$partialPct%")

        val blurredWork = createDenseBlurredBitmap(source)
        val blurredFull = if (blurredWork.width == source.width && blurredWork.height == source.height) {
            blurredWork
        } else {
            Bitmap.createScaledBitmap(blurredWork, source.width, source.height, true)
        }

        val srcPixels = IntArray(source.width * source.height)
        val blurPixels = IntArray(source.width * source.height)
        source.getPixels(srcPixels, 0, source.width, 0, 0, source.width, source.height)
        blurredFull.getPixels(blurPixels, 0, source.width, 0, 0, source.width, source.height)
        val outPixels = IntArray(source.width * source.height)
        for (i in srcPixels.indices) {
            val alpha = feather[i]
            if (alpha <= 0f) {
                outPixels[i] = srcPixels[i]
            } else if (alpha >= 1f) {
                outPixels[i] = blurPixels[i] or 0xFF000000.toInt()
            } else {
                val inv = 1f - alpha
                val s = srcPixels[i]
                val b = blurPixels[i]
                val r = (((s shr 16) and 0xFF) * inv + ((b shr 16) and 0xFF) * alpha).toInt().coerceIn(0, 255)
                val g = (((s shr 8) and 0xFF) * inv + ((b shr 8) and 0xFF) * alpha).toInt().coerceIn(0, 255)
                val bl = ((s and 0xFF) * inv + (b and 0xFF) * alpha).toInt().coerceIn(0, 255)
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        output.setPixels(outPixels, 0, source.width, 0, 0, source.width, source.height)

        return compressBitmap(output, mime).also {
            if (output !== source) output.recycle()
            blurredWork.recycle()
            if (blurredFull !== blurredWork) blurredFull.recycle()
        }
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
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    sum += mask[row + (x + k).coerceIn(0, width - 1)] * kernel[k + radius]
                }
                tmp[row + x] = sum
            }
        }
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0f
                for (k in -radius..radius) {
                    sum += tmp[(y + k).coerceIn(0, height - 1) * width + x] * kernel[k + radius]
                }
                out[y * width + x] = sum
            }
        }
        val maxVal = out.maxOrNull() ?: 0f
        if (maxVal > 0f) {
            for (i in out.indices) out[i] = (out[i] / maxVal).coerceIn(0f, 1f)
        }
        return out
    }

    private fun createDenseBlurredBitmap(source: Bitmap): Bitmap {
        val largestSide = maxOf(source.width, source.height)
        val workScale = minOf(1f, MAX_BLUR_WORK_DIM.toFloat() / largestSide.toFloat())
        val workW = (source.width * workScale).roundToInt().coerceAtLeast(1)
        val workH = (source.height * workScale).roundToInt().coerceAtLeast(1)
        val work = if (workW == source.width && workH == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, workW, workH, true)
        }
        val tinyDim = DENSE_PIXELATE_BASE
        val tiny = Bitmap.createScaledBitmap(work, tinyDim, tinyDim, true)
        var blurred = Bitmap.createScaledBitmap(tiny, work.width, work.height, false)
        tiny.recycle()
        val workDim = minOf(work.width, work.height)
        val radius = max(workDim / DENSE_BLUR_RADIUS_DIVISOR, 16)
        Log.d(TAG, "createDenseBlurredBitmap(): source=${source.width}x${source.height} work=${work.width}x${work.height} radius=$radius passes=$DENSE_BLUR_PASSES")
        repeat(DENSE_BLUR_PASSES) {
            val next = gaussianBlur(blurred, radius = radius)
            if (next !== blurred) blurred.recycle()
            blurred = next
        }
        if (work !== source) work.recycle()
        return blurred
    }

    private fun createFeatheredMask(mask: Bitmap): Bitmap {
        val r = (minOf(mask.width, mask.height) / 20 / 2).coerceAtLeast(10)
        Log.d(TAG, "createFeatheredMask(): mask=${mask.width}x${mask.height} radius=$r")
        return gaussianBlur(mask, radius = r)
    }

    private fun gaussianBlur(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 1 || height <= 1 || radius <= 0) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val sigma = radius / 2f
        val kernel = buildGaussianKernel(radius, sigma)
        val isAlpha = source.config == Bitmap.Config.ALPHA_8
        return if (isAlpha) {
            gaussianBlurAlpha(source, width, height, kernel, radius)
        } else {
            gaussianBlurArgb(source, width, height, kernel, radius)
        }
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

    private fun gaussianBlurArgb(source: Bitmap, width: Int, height: Int, kernel: FloatArray, radius: Int): Bitmap {
        val src = IntArray(width * height)
        val tmp = IntArray(width * height)
        val out = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        gaussianPassHorizontalArgb(src, tmp, width, height, kernel, radius)
        gaussianPassVerticalArgb(tmp, out, width, height, kernel, radius)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, width, 0, 0, width, height)
        }
    }

    private fun gaussianPassHorizontalArgb(src: IntArray, dst: IntArray, width: Int, height: Int, kernel: FloatArray, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    val color = src[row + sx]
                    val w = kernel[k + radius]
                    a += ((color ushr 24) and 0xFF) * w
                    r += ((color shr 16) and 0xFF) * w
                    g += ((color shr 8) and 0xFF) * w
                    b += (color and 0xFF) * w
                }
                dst[row + x] = (a.toInt().coerceIn(0, 255) shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun gaussianPassVerticalArgb(src: IntArray, dst: IntArray, width: Int, height: Int, kernel: FloatArray, radius: Int) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    val color = src[sy * width + x]
                    val w = kernel[k + radius]
                    a += ((color ushr 24) and 0xFF) * w
                    r += ((color shr 16) and 0xFF) * w
                    g += ((color shr 8) and 0xFF) * w
                    b += (color and 0xFF) * w
                }
                dst[y * width + x] = (a.toInt().coerceIn(0, 255) shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun gaussianBlurAlpha(source: Bitmap, width: Int, height: Int, kernel: FloatArray, radius: Int): Bitmap {
        val argb = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(source, 0f, 0f, Paint().apply { color = Color.WHITE })
        val src = IntArray(width * height)
        val tmp = IntArray(width * height)
        val out = IntArray(width * height)
        argb.getPixels(src, 0, width, 0, 0, width, height)
        argb.recycle()
        gaussianPassHorizontalArgb(src, tmp, width, height, kernel, radius)
        gaussianPassVerticalArgb(tmp, out, width, height, kernel, radius)
        val alphaBytes = ByteArray(width * height)
        for (i in out.indices) {
            alphaBytes[i] = ((out[i] ushr 24) and 0xFF).toByte()
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        result.copyPixelsFromBuffer(ByteBuffer.wrap(alphaBytes))
        return result
    }

    private fun compressBitmap(bitmap: Bitmap, mime: String?): ByteArray {
        val format = if (mime.equals("image/png", ignoreCase = true)) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 88, stream)
        return stream.toByteArray()
    }

    private fun expandRect(rect: RectF, imageW: Int, imageH: Int, paddingFraction: Float): RectF {
        val padX = (rect.width() * paddingFraction).coerceAtLeast(4f)
        val padY = (rect.height() * paddingFraction).coerceAtLeast(4f)
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
            if (kept.none { iou(it.rect, detection.rect) > iouThreshold }) {
                kept += detection
            }
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
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))

    override fun close() {
        sessionCache.values.forEach { runCatching { it.session.close() } }
        sessionCache.clear()
    }

    private enum class PaddingMode { TOP_LEFT_SQUARE, CENTER_LETTERBOX }

    private data class DetectorInput(
        val buffer: FloatBuffer,
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

    private data class AnalysisBitmap(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int,
        val scaleX: Float,
        val scaleY: Float,
    )

    private class CachedSession(
        val session: OrtSession,
        val modelName: String,
        @Volatile var lastUsedAt: Long,
    )

    private data class SlidingWindowResult(
        val fullFrame: Boolean,
        val regions: List<RectF>,
        val maxScore: Float,
    )
}
