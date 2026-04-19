package com.example.memegram

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.rememberAsyncImageBitmap
import com.example.memegram.utils.saveImageToGallery
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    initialMessageId: Int,
    chatName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onShowInChat: (Int) -> Unit,
    onReply: (Int) -> Unit,
) {
    val s = LocalStrings.current
    val messages by viewModel.messages.collectAsState()
    val mediaCache by viewModel.mediaCache.collectAsState()
    val messageSenders by viewModel.messageSenders.collectAsState()
    val memberProfiles by viewModel.memberProfiles.collectAsState()
    val isGroupChat by viewModel.isGroupChat.collectAsState()

    val photoMessages by remember {
        derivedStateOf { messages.filter { it.type == "image" } }
    }

    LaunchedEffect(photoMessages.isEmpty(), messages.isEmpty()) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (photoMessages.isEmpty()) onBack()
    }
    if (messages.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = Color.White) }
        return
    }
    if (photoMessages.isEmpty()) return

    val initialIndex = remember(initialMessageId, photoMessages) {
        photoMessages.indexOfFirst { it.id == initialMessageId }
            .let { if (it < 0) 0 else it }
    }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { photoMessages.size },
    )

    val currentMessage = photoMessages.getOrNull(pagerState.currentPage)
    var uiVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pagerState.currentPage, photoMessages) {
        val toLoad = listOfNotNull(
            photoMessages.getOrNull(pagerState.currentPage),
            photoMessages.getOrNull(pagerState.currentPage - 1),
            photoMessages.getOrNull(pagerState.currentPage + 1),
        )
        toLoad.forEach { msg ->
            val mid = msg.mediaId
            if (mid != null && mediaCache[mid] == null && msg.localPreviewBytes == null) {
                viewModel.loadMedia(mid, msg.encryptionMetadata)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val msg = photoMessages[page]
            ZoomablePhoto(
                message = msg,
                cachedBytes = msg.mediaId?.let { mediaCache[it] },
                onTap = { uiVisible = !uiVisible },
            )
        }

        // ── Top bar ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val msg = currentMessage
            val senderName = remember(msg, messageSenders, memberProfiles, isGroupChat, chatName) {
                if (msg == null) ""
                else if (msg.isOutgoing) s.you
                else if (!isGroupChat) {
                    chatName.ifEmpty {
                        val sid = messageSenders[msg.serverId]
                        sid?.let { memberProfiles[it]?.username } ?: s.interlocutor
                    }
                } else {
                    val sid = messageSenders[msg.serverId]
                    sid?.let { memberProfiles[it]?.username } ?: s.interlocutor
                }
            }
            val timeText = remember(msg) { msg?.let { formatPhotoFullTimestamp(it.timestamp) }.orEmpty() }
            val pageIndicator = remember(pagerState.currentPage, photoMessages.size) {
                if (photoMessages.size > 1) "${pagerState.currentPage + 1} / ${photoMessages.size}" else ""
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.sdp, vertical = 6.sdp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = s.back,
                            tint = Color.White,
                        )
                    }
                    Spacer(Modifier.width(4.sdp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = senderName,
                            color = Color.White,
                            fontSize = 15.ssp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (timeText.isNotEmpty()) {
                            Text(
                                text = timeText,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.ssp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (pageIndicator.isNotEmpty()) {
                        Text(
                            text = pageIndicator,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.ssp,
                            modifier = Modifier.padding(end = 8.sdp),
                        )
                    }
                }
            }
        }

        // ── Bottom action bar ────────────────────────────────────────
        AnimatedVisibility(
            visible = uiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(vertical = 6.sdp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val msg = currentMessage
                    val mediaId = msg?.mediaId
                    val bytesForSave = mediaId?.let { mediaCache[it] } ?: msg?.localPreviewBytes
                    val saveFileName = mediaId ?: msg?.id?.toString() ?: "image"

                    PhotoViewerActionButton(
                        icon = Icons.Default.Download,
                        label = s.saveToGallery,
                        enabled = bytesForSave != null,
                        onClick = {
                            val data = bytesForSave
                            if (data != null) {
                                scope.launch {
                                    val ok = saveImageToGallery(data, "memegram_$saveFileName.jpg")
                                    snackbarHostState.showSnackbar(
                                        if (ok) s.savedToGallery else s.saveFailed
                                    )
                                }
                            }
                        },
                    )
                    PhotoViewerActionButton(
                        icon = Icons.Default.LocationOn,
                        label = s.showInChat,
                        enabled = msg != null,
                        onClick = { msg?.let { onShowInChat(it.id) } },
                    )
                    PhotoViewerActionButton(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        label = s.reply,
                        enabled = msg != null,
                        onClick = { msg?.let { onReply(it.id) } },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.sdp),
        )
    }
}

@Composable
private fun PhotoViewerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    Column(
        modifier = Modifier
            .padding(horizontal = 8.sdp, vertical = 4.sdp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.sdp))
        }
        Text(
            text = label,
            color = tint,
            fontSize = 11.ssp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ZoomablePhoto(
    message: Message,
    cachedBytes: ByteArray?,
    onTap: () -> Unit,
) {
    val bytesForDecode = cachedBytes ?: message.localPreviewBytes
    val bitmap = rememberAsyncImageBitmap(
        bytes = bytesForDecode,
        cacheKey = message.mediaId?.let { "viewer:$it" },
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            CircularProgressIndicator(color = Color.White)
            return@Box
        }

        var scale by remember(message.id) { mutableStateOf(1f) }
        var offset by remember(message.id) { mutableStateOf(Offset.Zero) }

        val ratio = remember(bitmap) {
            (bitmap.width.toFloat() / bitmap.height.toFloat())
                .takeIf { it.isFinite() && it > 0f } ?: 1f
        }

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(message.id) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(message.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pointerCount = event.changes.count { it.pressed }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            val shouldHandle = pointerCount >= 2 || scale > 1.01f
                            if (shouldHandle) {
                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                if (newScale <= 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = newScale
                                    offset += panChange
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        )
        @Suppress("UNUSED_EXPRESSION") ratio
    }
}

private fun formatPhotoFullTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return try {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val day = local.dayOfMonth.toString().padStart(2, '0')
        val month = local.monthNumber.toString().padStart(2, '0')
        val year = local.year
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        "$day.$month.$year, $hour:$minute"
    } catch (_: Exception) {
        ""
    }
}
