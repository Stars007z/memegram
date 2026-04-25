package com.example.memegram.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

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
    val tintAlpha = (visibility * 0.85f * tintAlphaScale).coerceIn(0f, 1f)
    val tintColor = tint.copy(alpha = tintAlpha)

    Box(
        modifier = modifier.clip(shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tintColor)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.20f * visibility),
                            Color.White.copy(alpha = 0.02f * visibility),
                            Color.Black.copy(alpha = 0.05f * visibility),
                        ),
                    ),
                ),
        )
        content()
    }
}
