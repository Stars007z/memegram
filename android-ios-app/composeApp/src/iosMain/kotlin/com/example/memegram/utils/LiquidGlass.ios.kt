package com.example.memegram.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGColorRef
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CAGradientLayer
import platform.QuartzCore.CATransaction
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
    if (transparency >= 1f) {
        Box(modifier = modifier) { content() }
        return
    }

    val visibility = (1f - transparency).coerceIn(0f, 1f)
    val tintAlpha = (visibility * 0.58f * tintAlphaScale).coerceIn(0f, 1f)
    val effectAlpha = visibility

    val blurStyle: UIBlurEffectStyle = when {
        visibility > 0.7f -> UIBlurEffectStyle.UIBlurEffectStyleSystemMaterial
        visibility > 0.35f -> UIBlurEffectStyle.UIBlurEffectStyleSystemThinMaterial
        else -> UIBlurEffectStyle.UIBlurEffectStyleSystemUltraThinMaterial
    }

    val holder = remember { GlassHolder() }

    LaunchedEffect(blurStyle, effectAlpha, tint, tintAlpha, visibility) {
        holder.update(blurStyle, effectAlpha, tint, tintAlpha, visibility)
    }

    Box(modifier = modifier) {
        UIKitView(
            factory = {
                val container = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
                    setOpaque(false)
                    setBackgroundColor(UIColor.clearColor)
                    setClipsToBounds(true)
                    setUserInteractionEnabled(false)
                    layer.setMasksToBounds(true)
                }

                val effectView = UIVisualEffectView(
                    effect = UIBlurEffect.effectWithStyle(blurStyle),
                ).apply {
                    setOpaque(false)
                    setBackgroundColor(UIColor.clearColor)
                    setAlpha(effectAlpha.toDouble())
                    setUserInteractionEnabled(false)
                    setClipsToBounds(true)
                }

                val tintOverlay = UIView().apply {
                    setOpaque(false)
                    setBackgroundColor(tint.toUIColor(tintAlpha))
                    setUserInteractionEnabled(false)
                }

                val gradientLayer = CAGradientLayer().apply {
                    colors = listOf<CGColorRef?>(
                        UIColor.whiteColor.colorWithAlphaComponent(0.24 * visibility).CGColor,
                        UIColor.whiteColor.colorWithAlphaComponent(0.04 * visibility).CGColor,
                        UIColor.blackColor.colorWithAlphaComponent(0.05 * visibility).CGColor,
                    )
                }
                val gradientHost = UIView().apply {
                    setOpaque(false)
                    setBackgroundColor(UIColor.clearColor)
                    setUserInteractionEnabled(false)
                }
                gradientHost.layer.addSublayer(gradientLayer)

                effectView.contentView.addSubview(tintOverlay)
                effectView.contentView.addSubview(gradientHost)
                container.addSubview(effectView)

                holder.container = container
                holder.effectView = effectView
                holder.tintOverlay = tintOverlay
                holder.gradientHost = gradientHost
                holder.gradientLayer = gradientLayer
                holder.lastStyle = blurStyle
                holder.cornerRadius = 0.0
                holder.layout()
                container
            },
            modifier = Modifier.fillMaxSize(),
            update = { _ -> holder.layout() },
        )

        content()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class GlassHolder {
    var container: UIView? = null
    var effectView: UIVisualEffectView? = null
    var tintOverlay: UIView? = null
    var gradientHost: UIView? = null
    var gradientLayer: CAGradientLayer? = null
    var lastStyle: UIBlurEffectStyle? = null
    var cornerRadius: Double = 0.0

    fun update(
        style: UIBlurEffectStyle,
        effectAlpha: Float,
        tint: Color,
        tintAlpha: Float,
        visibility: Float,
    ) {
        if (style != lastStyle) {
            effectView?.setEffect(UIBlurEffect.effectWithStyle(style))
            lastStyle = style
        }
        effectView?.setAlpha(effectAlpha.coerceIn(0f, 1f).toDouble())
        tintOverlay?.setBackgroundColor(tint.toUIColor(tintAlpha))
        gradientLayer?.let { layer ->
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.colors = listOf<CGColorRef?>(
                UIColor.whiteColor.colorWithAlphaComponent(0.24 * visibility).CGColor,
                UIColor.whiteColor.colorWithAlphaComponent(0.04 * visibility).CGColor,
                UIColor.blackColor.colorWithAlphaComponent(0.05 * visibility).CGColor,
            )
            CATransaction.commit()
        }
        layout()
    }

    fun layout() {
        val c = container ?: return
        val bounds = c.bounds
        bounds.useContents {
            val w = size.width
            val h = size.height
            effectView?.setFrame(c.bounds)
            tintOverlay?.setFrame(c.bounds)
            gradientHost?.setFrame(c.bounds)
            gradientLayer?.setFrame(CGRectMake(0.0, 0.0, w, h))

            val target = (minOf(w, h) * 0.18).coerceAtMost(20.0)
            if (kotlin.math.abs(target - cornerRadius) > 0.1) {
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                c.layer.setCornerRadius(target)
                effectView?.layer?.setCornerRadius(target)
                cornerRadius = target
                CATransaction.commit()
            }
        }
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
