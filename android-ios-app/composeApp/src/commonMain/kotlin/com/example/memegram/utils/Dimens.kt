package com.example.memegram.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalScreenWidthDp = compositionLocalOf { 360f }

val LocalScreenHeightDp = compositionLocalOf { 640f }

val LocalTopBarImage = staticCompositionLocalOf<ImageBitmap?> { null }

val LocalTopBarTextColorOverride = staticCompositionLocalOf<Color?> { null }

private const val REFERENCE_WIDTH = 360f

private const val MAX_SCALE_WIDTH = 450f

@Composable
fun screenScaleFactor(): Float {
    val effectiveWidth = LocalScreenWidthDp.current.coerceAtMost(MAX_SCALE_WIDTH)
    return effectiveWidth / REFERENCE_WIDTH
}

val Int.sdp: Dp
    @Composable get() = (this * screenScaleFactor()).dp

val Float.sdp: Dp
    @Composable get() = (this * screenScaleFactor()).dp

val Double.sdp: Dp
    @Composable get() = (this.toFloat() * screenScaleFactor()).dp

val Int.ssp: TextUnit
    @Composable get() = (this * screenScaleFactor()).sp

val Float.ssp: TextUnit
    @Composable get() = (this * screenScaleFactor()).sp

@Composable
fun ImageTopAppBarBox(
    fallbackColor: Color,
    content: @Composable (containerColor: Color) -> Unit
) {
    val img = LocalTopBarImage.current
    val effectiveColor = if (img != null) Color.Transparent else fallbackColor
    Box {
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
        }
        content(effectiveColor)
    }
}

@Composable
fun resolveTopBarTextColor(topBarColor: Color): Color {
    val override = LocalTopBarTextColorOverride.current
    return override ?: if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
}
