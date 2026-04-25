package com.example.memegram.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

@Composable
expect fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    transparency: Float,
    tint: Color,
    shape: Shape,
    tintAlphaScale: Float = 1f,
    content: @Composable () -> Unit = {},
)

@Composable
fun LiquidGlassOverlay(
    modifier: Modifier = Modifier,
    transparency: Float,
    tint: Color,
    shape: Shape,
) {
    if (transparency >= 1f) return
    LiquidGlassSurface(
        modifier = modifier,
        transparency = transparency,
        tint = tint,
        shape = shape,
        tintAlphaScale = 0.6f,
    ) { Box(Modifier) }
}
