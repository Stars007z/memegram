package com.example.memegram

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class ImageCropDialog(
    private val imageUri: Uri,
    private val aspectRatioX: Float,
    private val aspectRatioY: Float,
    private val onImageCropped: (Uri) -> Unit
) : DialogFragment() {

    private lateinit var cropView: CropImageView
    private var originalBitmap: Bitmap? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val container = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(40, 40, 40, 40)
        }

        originalBitmap = loadBitmap(requireContext(), imageUri)

        cropView = CropImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (400 * resources.displayMetrics.density).toInt()
            )
        }

        originalBitmap?.let { cropView.setImageBitmap(it, aspectRatioX, aspectRatioY) }
        container.addView(cropView)

        val buttonsLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, 20, 0, 0)
        }

        val btnCancel = Button(requireContext()).apply {
            text = "Cancel"
            setOnClickListener { dismiss() }
        }

        val btnDone = Button(requireContext()).apply {
            text = "Done"
            setOnClickListener {
                originalBitmap?.let { bitmap ->
                    val croppedBitmap = cropView.getCroppedBitmap(bitmap)
                    saveCroppedImage(croppedBitmap)
                }
            }
        }

        buttonsLayout.addView(btnCancel)
        buttonsLayout.addView(btnDone)

        val mainLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(container)
            addView(buttonsLayout)
        }

        builder.setView(mainLayout)
        return builder.create()
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveCroppedImage(bitmap: Bitmap) {
        try {
            val file = File(requireContext().cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            onImageCropped(Uri.fromFile(file))
            dismiss()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        originalBitmap?.recycle()
        originalBitmap = null
    }
}

class CropImageView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val overlayPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
    }

    private var cropRect = RectF()
    private var imageRect = RectF()
    private var aspectRatioX = 1f
    private var aspectRatioY = 1f

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    fun setImageBitmap(bmp: Bitmap, ratioX: Float, ratioY: Float) {
        bitmap = bmp
        aspectRatioX = ratioX
        aspectRatioY = ratioY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = bitmap ?: return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val bmpWidth = bmp.width.toFloat()
        val bmpHeight = bmp.height.toFloat()

        val scale = min(viewWidth / bmpWidth, viewHeight / bmpHeight)
        val scaledWidth = bmpWidth * scale
        val scaledHeight = bmpHeight * scale
        val left = (viewWidth - scaledWidth) / 2
        val top = (viewHeight - scaledHeight) / 2

        imageRect.set(left, top, left + scaledWidth, top + scaledHeight)

        canvas.drawBitmap(bmp, null, imageRect, paint)

        if (cropRect.isEmpty) {
            val cropWidth: Float
            val cropHeight: Float

            if (aspectRatioX >= aspectRatioY) {
                cropWidth = min(scaledWidth * 0.8f, scaledHeight * aspectRatioX / aspectRatioY * 0.8f)
                cropHeight = cropWidth * aspectRatioY / aspectRatioX
            } else {
                cropHeight = min(scaledHeight * 0.8f, scaledWidth * aspectRatioY / aspectRatioX * 0.8f)
                cropWidth = cropHeight * aspectRatioX / aspectRatioY
            }

            val cropLeft = (viewWidth - cropWidth) / 2
            val cropTop = (viewHeight - cropHeight) / 2
            cropRect.set(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
        }

        canvas.drawRect(0f, 0f, viewWidth, cropRect.top, overlayPaint)
        canvas.drawRect(0f, cropRect.bottom, viewWidth, viewHeight, overlayPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, overlayPaint)
        canvas.drawRect(cropRect.right, cropRect.top, viewWidth, cropRect.bottom, overlayPaint)

        canvas.drawRect(cropRect, gridPaint)

        val cellWidth = cropRect.width() / 3
        val cellHeight = cropRect.height() / 3

        for (i in 1..2) {
            canvas.drawLine(
                cropRect.left + i * cellWidth,
                cropRect.top,
                cropRect.left + i * cellWidth,
                cropRect.bottom,
                gridPaint
            )
            canvas.drawLine(
                cropRect.left,
                cropRect.top + i * cellHeight,
                cropRect.right,
                cropRect.top + i * cellHeight,
                gridPaint
            )
        }

        val cornerLength = 30f

        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + cornerLength, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + cornerLength, cornerPaint)

        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right - cornerLength, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + cornerLength, cornerPaint)

        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + cornerLength, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left, cropRect.bottom - cornerLength, cornerPaint)

        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right - cornerLength, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right, cropRect.bottom - cornerLength, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY

                cropRect.offset(dx, dy)

                if (cropRect.left < imageRect.left) {
                    cropRect.offset(imageRect.left - cropRect.left, 0f)
                }
                if (cropRect.right > imageRect.right) {
                    cropRect.offset(imageRect.right - cropRect.right, 0f)
                }
                if (cropRect.top < imageRect.top) {
                    cropRect.offset(0f, imageRect.top - cropRect.top)
                }
                if (cropRect.bottom > imageRect.bottom) {
                    cropRect.offset(0f, imageRect.bottom - cropRect.bottom)
                }

                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun getCroppedBitmap(originalBitmap: Bitmap): Bitmap {
        val scaleX = originalBitmap.width.toFloat() / imageRect.width()
        val scaleY = originalBitmap.height.toFloat() / imageRect.height()

        val cropX = ((cropRect.left - imageRect.left) * scaleX).toInt().coerceAtLeast(0)
        val cropY = ((cropRect.top - imageRect.top) * scaleY).toInt().coerceAtLeast(0)
        val cropWidth = (cropRect.width() * scaleX).toInt().coerceAtMost(originalBitmap.width - cropX)
        val cropHeight = (cropRect.height() * scaleY).toInt().coerceAtMost(originalBitmap.height - cropY)

        return Bitmap.createBitmap(originalBitmap, cropX, cropY, cropWidth, cropHeight)
    }
}
