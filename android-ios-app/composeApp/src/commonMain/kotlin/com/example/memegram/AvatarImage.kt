package com.example.memegram

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import org.koin.compose.koinInject
import com.example.memegram.utils.sdp

@Composable
fun AvatarImage(
    mediaId: String?,
    size: Dp,
    fallbackLetter: String,
    backgroundColor: Color = Color(0xFF6075F2),
    textColor: Color = Color.White,
    textStyle: TextStyle? = null,
    borderWidth: Dp = 0.sdp,
    borderColor: Color = Color.White
) {
    val avatarCache = koinInject<AvatarCache>()
    var bytes by remember(mediaId) { mutableStateOf(mediaId?.let { avatarCache.getCached(it) }) }

    LaunchedEffect(mediaId) {
        if (mediaId != null && bytes == null) {
            bytes = avatarCache.load(mediaId)
        }
    }

    val modifier = Modifier
        .size(size)
        .clip(CircleShape)
        .background(backgroundColor)
        .then(
            if (borderWidth > 0.sdp) Modifier.border(borderWidth, borderColor, CircleShape)
            else Modifier
        )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (bytes != null) {
            val bitmap = remember(bytes) { bytes!!.decodeToImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = fallbackLetter,
                color = textColor,
                fontWeight = FontWeight.Bold,
                style = textStyle ?: MaterialTheme.typography.titleMedium
            )
        }
    }
}
