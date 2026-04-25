package com.example.memegram.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIVisualEffectView
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun LiquidGlassSurface(
    modifier: Modifier,
    transparency: Float,
    tint: Color,
    shape: Shape,
    tintAlphaScale: Float,
    content: @Composable () -> Unit,
) {
    if (transparency >= 1f) return

    val visibility = (1f - transparency).coerceIn(0f, 1f)
    val tintAlpha = (visibility * 0.65f * tintAlphaScale).coerceIn(0f, 1f)

    val blurStyle: UIBlurEffectStyle = when {
        visibility > 0.7f -> UIBlurEffectStyle.UIBlurEffectStyleSystemMaterial
        visibility > 0.35f -> UIBlurEffectStyle.UIBlurEffectStyleSystemThinMaterial
        else -> UIBlurEffectStyle.UIBlurEffectStyleSystemUltraThinMaterial
    }

    val holder = remember { GlassHolder() }

    LaunchedEffect(blurStyle, tint, tintAlpha) {
        holder.update(blurStyle, tint, tintAlpha)
    }

    Box(modifier = modifier.clip(shape)) {
        UIKitView(
            factory = {
                val container = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
                    setOpaque(false)
                    setBackgroundColor(UIColor.clearColor)
                    setClipsToBounds(true)
                    setUserInteractionEnabled(false)
                }
                val effectView = UIVisualEffectView(effect = UIBlurEffect.effectWithStyle(blurStyle)).apply {
                    setBackgroundColor(UIColor.clearColor)
                    setUserInteractionEnabled(false)
                }
                val tintOverlay = UIView().apply {
                    setBackgroundColor(tint.toUIColor(tintAlpha))
                    setUserInteractionEnabled(false)
                }
                container.addSubview(effectView)
                container.addSubview(tintOverlay)
                holder.container = container
                holder.effectView = effectView
                holder.tintOverlay = tintOverlay
                holder.lastStyle = blurStyle
                holder.layout()
                container
            },
            modifier = Modifier.fillMaxSize(),
            update = { _ -> holder.layout() },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f * visibility),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.04f * visibility),
                        ),
                    ),
                ),
        )
        content()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class GlassHolder {
    var container: UIView? = null
    var effectView: UIVisualEffectView? = null
    var tintOverlay: UIView? = null
    var lastStyle: UIBlurEffectStyle? = null

    fun update(style: UIBlurEffectStyle, tint: Color, tintAlpha: Float) {
        if (style != lastStyle) {
            effectView?.setEffect(UIBlurEffect.effectWithStyle(style))
            lastStyle = style
        }
        tintOverlay?.setBackgroundColor(tint.toUIColor(tintAlpha))
        layout()
    }

    fun layout() {
        val bounds = container?.bounds ?: return
        effectView?.setFrame(bounds)
        tintOverlay?.setFrame(bounds)
    }
}

private fun Color.toUIColor(alphaOverride: Float? = null): UIColor {
    val argb = this.toArgb()
    val r = ((argb shr 16) and 0xFF) / 255.0
    val g = ((argb shr 8) and 0xFF) / 255.0
    val b = (argb and 0xFF) / 255.0
    val a = alphaOverride?.toDouble() ?: (((argb shr 24) and 0xFF) / 255.0)
    return UIColor.colorWithRed(red = r, green = g, blue = b, alpha = a)
}
