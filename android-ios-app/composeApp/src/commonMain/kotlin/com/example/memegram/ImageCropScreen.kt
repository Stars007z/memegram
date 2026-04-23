package com.example.memegram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.localization.LocalStrings
import kotlin.math.roundToInt
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp

private enum class DragMode {
    NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR
}

@Composable
fun ImageCropScreen(
    imageBytes: ByteArray,
    aspectRatio: Float = 0f,
    onCropped: (ByteArray) -> Unit,
    onCancel: () -> Unit
) {
    val s = LocalStrings.current
    val imageBitmap = remember(imageBytes) {
        runCatching { imageBytes.decodeToImageBitmap() }.getOrNull()
    }

    if (imageBitmap == null) {
        onCancel()
        return
    }

    val imgW = imageBitmap.width.toFloat()
    val imgH = imageBitmap.height.toFloat()

    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val imageRect by remember(canvasSize, imgW, imgH) {
        derivedStateOf {
            if (canvasSize == Size.Zero) Rect.Zero
            else {
                val scaleX = canvasSize.width / imgW
                val scaleY = canvasSize.height / imgH
                val scale = minOf(scaleX, scaleY)
                val dstW = imgW * scale
                val dstH = imgH * scale
                val offsetX = (canvasSize.width - dstW) / 2f
                val offsetY = (canvasSize.height - dstH) / 2f
                Rect(offsetX, offsetY, offsetX + dstW, offsetY + dstH)
            }
        }
    }

    val drawnRect by remember(canvasSize, imgW, imgH) {
        derivedStateOf {
            if (imageRect == Rect.Zero) Rect.Zero
            else {
                val l = imageRect.left.roundToInt()
                val t = imageRect.top.roundToInt()
                val w = imageRect.width.roundToInt()
                val h = imageRect.height.roundToInt()
                Rect(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat())
            }
        }
    }

    var cropRect by remember { mutableStateOf(Rect.Zero) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(drawnRect) {
        if (drawnRect != Rect.Zero && !initialized) {
            val iRect = drawnRect
            if (aspectRatio > 0f) {
                val fw: Float
                val fh: Float
                if (iRect.width / iRect.height > aspectRatio) {
                    fh = iRect.height * 0.75f
                    fw = fh * aspectRatio
                } else {
                    fw = iRect.width * 0.75f
                    fh = fw / aspectRatio
                }
                val cx = iRect.left + (iRect.width - fw) / 2f
                val cy = iRect.top + (iRect.height - fh) / 2f
                cropRect = Rect(cx, cy, cx + fw, cy + fh)
            } else {
                val size = minOf(iRect.width, iRect.height) * 0.75f
                val cx = iRect.left + (iRect.width - size) / 2f
                val cy = iRect.top + (iRect.height - size) / 2f
                cropRect = Rect(cx, cy, cx + size, cy + size)
            }
            initialized = true
        }
    }

    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    val handleSize = 80f

    fun detectDragMode(pos: Offset): DragMode {
        val r = cropRect
        val hs = handleSize
        return when {
            Rect(r.left - hs, r.top - hs, r.left + hs, r.top + hs).contains(pos) -> DragMode.RESIZE_TL
            Rect(r.right - hs, r.top - hs, r.right + hs, r.top + hs).contains(pos) -> DragMode.RESIZE_TR
            Rect(r.left - hs, r.bottom - hs, r.left + hs, r.bottom + hs).contains(pos) -> DragMode.RESIZE_BL
            Rect(r.right - hs, r.bottom - hs, r.right + hs, r.bottom + hs).contains(pos) -> DragMode.RESIZE_BR
            r.contains(pos) -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1F))
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.sdp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(drawnRect) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragMode = detectDragMode(offset)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val iRect = drawnRect
                                val minSize = 60f

                                when (dragMode) {
                                    DragMode.MOVE -> {
                                        val newLeft = (cropRect.left + dragAmount.x)
                                            .coerceIn(iRect.left, iRect.right - cropRect.width)
                                        val newTop = (cropRect.top + dragAmount.y)
                                            .coerceIn(iRect.top, iRect.bottom - cropRect.height)
                                        cropRect = Rect(
                                            newLeft, newTop,
                                            newLeft + cropRect.width,
                                            newTop + cropRect.height
                                        )
                                    }
                                    DragMode.RESIZE_TL -> {
                                        var newLeft = (cropRect.left + dragAmount.x).coerceIn(iRect.left, cropRect.right - minSize)
                                        var newTop = (cropRect.top + dragAmount.y).coerceIn(iRect.top, cropRect.bottom - minSize)
                                        if (aspectRatio > 0f) {
                                            val w = cropRect.right - newLeft
                                            val h = w / aspectRatio
                                            newTop = cropRect.bottom - h
                                            if (newTop < iRect.top) {
                                                newTop = iRect.top
                                                val h2 = cropRect.bottom - newTop
                                                newLeft = cropRect.right - h2 * aspectRatio
                                            }
                                        }
                                        cropRect = Rect(newLeft, newTop, cropRect.right, cropRect.bottom)
                                    }
                                    DragMode.RESIZE_TR -> {
                                        var newRight = (cropRect.right + dragAmount.x).coerceIn(cropRect.left + minSize, iRect.right)
                                        var newTop = (cropRect.top + dragAmount.y).coerceIn(iRect.top, cropRect.bottom - minSize)
                                        if (aspectRatio > 0f) {
                                            val w = newRight - cropRect.left
                                            val h = w / aspectRatio
                                            newTop = cropRect.bottom - h
                                            if (newTop < iRect.top) {
                                                newTop = iRect.top
                                                val h2 = cropRect.bottom - newTop
                                                newRight = cropRect.left + h2 * aspectRatio
                                            }
                                        }
                                        cropRect = Rect(cropRect.left, newTop, newRight, cropRect.bottom)
                                    }
                                    DragMode.RESIZE_BL -> {
                                        var newLeft = (cropRect.left + dragAmount.x).coerceIn(iRect.left, cropRect.right - minSize)
                                        var newBottom = (cropRect.bottom + dragAmount.y).coerceIn(cropRect.top + minSize, iRect.bottom)
                                        if (aspectRatio > 0f) {
                                            val w = cropRect.right - newLeft
                                            newBottom = cropRect.top + w / aspectRatio
                                            if (newBottom > iRect.bottom) {
                                                newBottom = iRect.bottom
                                                val h2 = newBottom - cropRect.top
                                                newLeft = cropRect.right - h2 * aspectRatio
                                            }
                                        }
                                        cropRect = Rect(newLeft, cropRect.top, cropRect.right, newBottom)
                                    }
                                    DragMode.RESIZE_BR -> {
                                        var newRight = (cropRect.right + dragAmount.x).coerceIn(cropRect.left + minSize, iRect.right)
                                        var newBottom = (cropRect.bottom + dragAmount.y).coerceIn(cropRect.top + minSize, iRect.bottom)
                                        if (aspectRatio > 0f) {
                                            val w = newRight - cropRect.left
                                            newBottom = cropRect.top + w / aspectRatio
                                            if (newBottom > iRect.bottom) {
                                                newBottom = iRect.bottom
                                                val h2 = newBottom - cropRect.top
                                                newRight = cropRect.left + h2 * aspectRatio
                                            }
                                        }
                                        cropRect = Rect(cropRect.left, cropRect.top, newRight, newBottom)
                                    }
                                    DragMode.NONE -> {}
                                }
                            },
                            onDragEnd = { dragMode = DragMode.NONE }
                        )
                    }
            ) {
                canvasSize = size

                if (drawnRect == Rect.Zero) return@Canvas

                val dstLeft = drawnRect.left.roundToInt()
                val dstTop = drawnRect.top.roundToInt()
                val dstW = drawnRect.width.roundToInt()
                val dstH = drawnRect.height.roundToInt()

                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(imageBitmap.width, imageBitmap.height),
                    dstOffset = IntOffset(dstLeft, dstTop),
                    dstSize = IntSize(dstW, dstH)
                )

                val overlayColor = Color.Black.copy(alpha = 0.55f)
                drawRect(overlayColor, Offset(drawnRect.left, drawnRect.top),
                    Size(drawnRect.width, (cropRect.top - drawnRect.top).coerceAtLeast(0f)))
                drawRect(overlayColor, Offset(drawnRect.left, cropRect.bottom),
                    Size(drawnRect.width, (drawnRect.bottom - cropRect.bottom).coerceAtLeast(0f)))
                drawRect(overlayColor, Offset(drawnRect.left, cropRect.top),
                    Size((cropRect.left - drawnRect.left).coerceAtLeast(0f), cropRect.height))
                drawRect(overlayColor, Offset(cropRect.right, cropRect.top),
                    Size((drawnRect.right - cropRect.right).coerceAtLeast(0f), cropRect.height))

                drawRect(
                    color = Color.White,
                    topLeft = Offset(cropRect.left, cropRect.top),
                    size = Size(cropRect.width, cropRect.height),
                    style = Stroke(width = 2f)
                )

                val thirdW = cropRect.width / 3f
                val thirdH = cropRect.height / 3f
                val gridColor = Color.White.copy(alpha = 0.4f)
                for (i in 1..2) {
                    drawLine(gridColor,
                        Offset(cropRect.left + thirdW * i, cropRect.top),
                        Offset(cropRect.left + thirdW * i, cropRect.bottom),
                        strokeWidth = 1f)
                    drawLine(gridColor,
                        Offset(cropRect.left, cropRect.top + thirdH * i),
                        Offset(cropRect.right, cropRect.top + thirdH * i),
                        strokeWidth = 1f)
                }

                val cornerLen = 24f
                val cornerWidth = 4f
                val white = Color.White
                listOf(
                    Offset(cropRect.left, cropRect.top),
                    Offset(cropRect.right, cropRect.top),
                    Offset(cropRect.left, cropRect.bottom),
                    Offset(cropRect.right, cropRect.bottom)
                ).forEachIndexed { idx, corner ->
                    val dx = if (idx % 2 == 0) 1f else -1f
                    val dy = if (idx < 2) 1f else -1f
                    drawLine(white, corner, Offset(corner.x + cornerLen * dx, corner.y), strokeWidth = cornerWidth)
                    drawLine(white, corner, Offset(corner.x, corner.y + cornerLen * dy), strokeWidth = cornerWidth)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.sdp, vertical = 16.sdp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(16.sdp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(50.sdp),
                shape = RoundedCornerShape(12.sdp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text(s.cancel, fontSize = 16.ssp)
            }
            Button(
                onClick = {
                    val iRect = drawnRect
                    if (iRect.width > 0 && iRect.height > 0) {
                        val scaleX = imgW / iRect.width
                        val scaleY = imgH / iRect.height
                        val px = ((cropRect.left - iRect.left) * scaleX).roundToInt().coerceAtLeast(0)
                        val py = ((cropRect.top - iRect.top) * scaleY).roundToInt().coerceAtLeast(0)
                        val pw = (cropRect.width * scaleX).roundToInt().coerceAtMost(imgW.roundToInt() - px)
                        val ph = (cropRect.height * scaleY).roundToInt().coerceAtMost(imgH.roundToInt() - py)
                        val cropped = imageBytes.cropImage(px, py, pw, ph)
                        onCropped(cropped)
                    }
                },
                modifier = Modifier.weight(1f).height(50.sdp),
                shape = RoundedCornerShape(12.sdp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6075F2)
                )
            ) {
                Text(s.save, fontSize = 16.ssp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
