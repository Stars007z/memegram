package com.example.memegram

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.audio.GlobalAudioPlayer
import com.example.memegram.audio.VoicePlaybackBar
import com.example.memegram.data.gallery.AttachItem
import com.example.memegram.data.gallery.GalleryThumb
import com.example.memegram.data.gallery.buildGallerySections
import com.example.memegram.data.gallery.rememberGalleryLoader
import com.example.memegram.utils.saveImageToGallery
import com.example.memegram.localization.LocalStrings
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox
import com.example.memegram.utils.LocalScreenWidthDp
import com.example.memegram.utils.rememberAsyncImageBitmap
import kotlin.math.roundToInt

private const val CHAT_BUBBLE_MAX_IMAGE_DIM = 1280
private const val ALBUM_CELL_MAX_IMAGE_DIM = 720

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    topBarColor: Color,
    chatName: String,
    onBack: () -> Unit,
    onProfileClick: () -> Unit,
    onPhotoClick: (Int) -> Unit = {},
    scrollToMessageId: Int? = null,
    replyToMessageId: Int? = null,
    onScrollToConsumed: () -> Unit = {},
    onReplyToConsumed: () -> Unit = {},
    viewModel: ChatViewModel
) {
    val messages       by viewModel.messages.collectAsState()
    val inputText      by viewModel.inputText.collectAsState()
    val s = LocalStrings.current
    val chatBgColor    by viewModel.chatBgColor.collectAsState()
    val myBubbleColor  by viewModel.myBubbleColor.collectAsState()
    val theirBubbleColor by viewModel.theirBubbleColor.collectAsState()
    val chatBgImage    by viewModel.chatBgImage.collectAsState()
    val myBubbleImage  by viewModel.myBubbleImage.collectAsState()
    val theirBubbleImage by viewModel.theirBubbleImage.collectAsState()
    val mediaCache by viewModel.mediaCache.collectAsState()
    val downloadingFiles by viewModel.downloadingFiles.collectAsState()
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White

    val globalAudioPlayer = koinInject<GlobalAudioPlayer>()
    val audioPlaybackState by globalAudioPlayer.state.collectAsState()

    val listState = rememberLazyListState()

    val chatItems by remember { derivedStateOf { groupMessages(messages) } }

    val lastVisibleIncomingServerId by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || chatItems.isEmpty()) return@derivedStateOf null
            visible
                .flatMap { info -> chatItems.getOrNull(info.index)?.allMessages ?: emptyList() }
                .lastOrNull { !it.isOutgoing && it.serverId.isNotBlank() }
                ?.serverId
        }
    }

    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(lastVisibleIncomingServerId, isLoading) {
        if (isLoading) return@LaunchedEffect
        lastVisibleIncomingServerId?.let(viewModel::markMessagesRead)
    }

    var scrollRestored by remember { mutableStateOf(false) }
    val initialUnreadCount by viewModel.initialUnreadCount.collectAsState()

    LaunchedEffect(chatItems.size) {
        if (chatItems.isEmpty()) return@LaunchedEffect
        if (!scrollRestored) {
            val convId = viewModel.currentConversationId
            val saved = convId?.let { ChatScrollCache.restore(it) }
            if (saved != null) {
                listState.scrollToItem(saved.first.coerceAtMost(chatItems.lastIndex), saved.second)
            } else if (initialUnreadCount > 0 && initialUnreadCount < messages.size) {
                val lastReadMsgIdx = (messages.size - initialUnreadCount - 1).coerceAtLeast(0)
                val lastReadMsg = messages[lastReadMsgIdx]
                val chatItemIdx = chatItems.indexOfFirst { item ->
                    item.allMessages.any { it.id == lastReadMsg.id }
                }.coerceAtLeast(0)
                listState.scrollToItem(chatItemIdx)
            } else {
                listState.scrollToItem(chatItems.lastIndex)
            }
            scrollRestored = true
        } else {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisible != null && lastVisible.index >= chatItems.size - 3) {
                listState.animateScrollToItem(chatItems.lastIndex)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val convId = viewModel.currentConversationId ?: return@onDispose
            ChatScrollCache.save(
                convId,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (chatItems.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= chatItems.size - 3) {
            listState.animateScrollToItem(chatItems.lastIndex)
        }
    }

    var attachments by remember { mutableStateOf<List<AttachItem>>(emptyList()) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var pendingGallery by remember { mutableStateOf<Set<GalleryThumb>>(emptySet()) }
    val galleryLoader = rememberGalleryLoader()
    var galleryThumbs by remember { mutableStateOf<List<GalleryThumb>>(emptyList()) }
    var galleryLoading by remember { mutableStateOf(false) }
    val gridState      = rememberLazyGridState()
    val gallerySections = remember(galleryThumbs) { buildGallerySections(galleryThumbs) }
    val screenWidthDp = LocalScreenWidthDp.current
    val defaultGridColumns = remember(screenWidthDp) {
        (screenWidthDp / 120f).roundToInt().coerceIn(2, 6)
    }
    var gridColumns by remember(defaultGridColumns) { mutableIntStateOf(defaultGridColumns) }
    var pinchAccumulatedScale by remember { mutableFloatStateOf(1f) }

    var galleryTotal by remember { mutableIntStateOf(0) }
    var galleryReachedEnd by remember { mutableStateOf(false) }
    val galleryPageSize = 60

    LaunchedEffect(showAttachSheet) {
        if (!showAttachSheet) {
            galleryThumbs = emptyList()
            galleryTotal = 0
            galleryReachedEnd = false
        }
    }

    LaunchedEffect(showAttachSheet, galleryLoader.isPermissionGranted) {
        if (showAttachSheet && galleryLoader.isPermissionGranted && galleryThumbs.isEmpty() && !galleryLoading) {
            galleryLoading = true
            galleryTotal = runCatching { galleryLoader.totalCount() }.getOrDefault(0)
            val firstPage = runCatching { galleryLoader.loadPage(0, galleryPageSize) }.getOrDefault(emptyList())
            galleryThumbs = firstPage
            galleryReachedEnd = firstPage.size < galleryPageSize || firstPage.size >= galleryTotal
            galleryLoading = false
        }
    }

    LaunchedEffect(gridState, showAttachSheet) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (!showAttachSheet) return@collect
            if (galleryLoading || galleryReachedEnd) return@collect
            if (total <= 0) return@collect
            if (lastVisible >= total - 12) {
                galleryLoading = true
                val next = runCatching { galleryLoader.loadPage(galleryThumbs.size, galleryPageSize) }
                    .getOrDefault(emptyList())
                if (next.isEmpty()) {
                    galleryReachedEnd = true
                } else {
                    galleryThumbs = galleryThumbs + next
                    if (next.size < galleryPageSize) galleryReachedEnd = true
                    if (galleryThumbs.size >= galleryTotal && galleryTotal > 0) galleryReachedEnd = true
                }
                galleryLoading = false
            }
        }
    }

    val imagePicker = com.example.memegram.picker.rememberImagePicker(
        multiple = true,
    ) { byteList ->
        if (byteList.isEmpty()) return@rememberImagePicker
        val stamp = kotlin.random.Random.Default.nextInt().toUInt().toString(16)
        val newAttachments = byteList.mapIndexed { idx, bytes ->
            AttachItem.FromBytes(bytes = bytes, name = "IMG_${stamp}_$idx.jpg", mime = "image/jpeg")
        }
        attachments = attachments + newAttachments
    }

    val filePicker = rememberFilePickerLauncher(
        type = PickerType.File(),
        mode = PickerMode.Multiple()
    ) { files ->
        files?.forEach { attachments = attachments + AttachItem.FromPicker(it, asFile = true) }
    }

    var isSearchMode     by remember { mutableStateOf(false) }
    var searchQuery      by remember { mutableStateOf("") }
    var currentMatchIdx  by remember { mutableIntStateOf(0) }
    val searchFocus      = remember { FocusRequester() }

    val searchMatches = remember(chatItems, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else chatItems.mapIndexedNotNull { i, item ->
            if (item.allMessages.any { it.text.contains(searchQuery, ignoreCase = true) }) i else null
        }
    }
    val currentMatchMsgId = remember(searchMatches, currentMatchIdx) {
        if (searchMatches.isNotEmpty()) {
            val item = chatItems[searchMatches[currentMatchIdx]]
            item.allMessages.firstOrNull { it.text.contains(searchQuery, ignoreCase = true) }?.id ?: -1
        } else -1
    }

    LaunchedEffect(searchQuery)   { currentMatchIdx = if (searchMatches.isNotEmpty()) searchMatches.size - 1 else 0 }
    LaunchedEffect(isSearchMode)  { if (isSearchMode) searchFocus.requestFocus() else searchQuery = "" }
    LaunchedEffect(currentMatchIdx, searchMatches.size) {
        if (searchMatches.isNotEmpty()) {
            val origIdx = searchMatches[currentMatchIdx]
            listState.animateScrollToItem(origIdx)
        }
    }

    var showMenu        by remember { mutableStateOf(false) }
    var showMuteDialog  by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val isGroupChat by viewModel.isGroupChat.collectAsState()
    val messageSenders by viewModel.messageSenders.collectAsState()
    val memberProfiles by viewModel.memberProfiles.collectAsState()
    val peerAvatarMediaId by viewModel.peerAvatarMediaId.collectAsState()
    val isPeerBlocked by viewModel.isPeerBlocked.collectAsState()
    val isBlockedByPeer by viewModel.isBlockedByPeer.collectAsState()
    val isMlsBroken by viewModel.isMlsBroken.collectAsState()
    var showBlockConfirm by remember { mutableStateOf(false) }

    val replyingTo by viewModel.replyingTo.collectAsState()
    val replyContext by viewModel.replyContext.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var highlightedMessageId by remember { mutableStateOf<Int?>(null) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(scrollToMessageId, chatItems) {
        val target = scrollToMessageId ?: return@LaunchedEffect
        if (chatItems.isEmpty()) return@LaunchedEffect
        val idx = chatItems.indexOfFirst { item -> item.allMessages.any { it.id == target } }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            highlightedMessageId = target
        }
        onScrollToConsumed()
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1500)
            highlightedMessageId = null
        }
    }

    LaunchedEffect(replyToMessageId, messages) {
        val target = replyToMessageId ?: return@LaunchedEffect
        val msg = messages.firstOrNull { it.id == target } ?: run {
            onReplyToConsumed()
            return@LaunchedEffect
        }
        viewModel.setReplyTo(msg)
        onReplyToConsumed()
        delay(50)
        runCatching { inputFocusRequester.requestFocus() }
        keyboardController?.show()
    }

    val errorMessage by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var messagesToDelete by remember { mutableStateOf<List<Message>?>(null) }

    val recordState     by viewModel.recordState.collectAsState()
    val recordAmps      by viewModel.voiceAmplitudes.collectAsState()
    val recordDuration  by viewModel.voiceDurationMs.collectAsState()

    var isRecordingVoice by remember { mutableStateOf(false) }

    var showFullScreenAvatar by remember { mutableStateOf(false) }
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false; pendingGallery = emptySet() },
            sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.sdp, vertical = 8.sdp),
                        verticalAlignment         = Alignment.CenterVertically,
                        horizontalArrangement     = Arrangement.SpaceBetween
                    ) {
                        Text(s.gallery, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.sdp)) {
                            OutlinedButton(
                                onClick        = { filePicker.launch(); showAttachSheet = false },
                                contentPadding = PaddingValues(horizontal = 12.sdp, vertical = 6.sdp)
                            ) {
                                Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(16.sdp))
                                Spacer(Modifier.width(4.sdp))
                                Text(s.file, fontSize = 13.ssp)
                            }
                            OutlinedButton(
                                onClick        = { imagePicker(); showAttachSheet = false },
                                contentPadding = PaddingValues(horizontal = 12.sdp, vertical = 6.sdp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.sdp))
                                Spacer(Modifier.width(4.sdp))
                                Text(s.all, fontSize = 13.ssp)
                            }
                        }
                    }

                    HorizontalDivider()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        when {
                            galleryLoading -> {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                            galleryThumbs.isEmpty() -> {
                                Column(
                                    modifier            = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(48.sdp), tint = Color.Gray)
                                    Spacer(Modifier.height(8.sdp))
                                    Text(s.noGalleryAccess, color = Color.Gray)
                                    Spacer(Modifier.height(8.sdp))
                                    Button(onClick = { imagePicker(); showAttachSheet = false }) {
                                        Text(s.openGallery)
                                    }
                                }
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                                    val pressed = event.changes.filter { it.pressed }
                                                    if (pressed.size >= 2) {
                                                        event.changes.forEach { it.consume() }
                                                        var prevDist = (pressed[0].position - pressed[1].position).getDistance()

                                                        var tracking = true
                                                        while (tracking) {
                                                            val next = awaitPointerEvent(PointerEventPass.Final)
                                                            val nextPressed = next.changes.filter { it.pressed }
                                                            if (nextPressed.size >= 2) {
                                                                next.changes.forEach { it.consume() }
                                                                val dist = (nextPressed[0].position - nextPressed[1].position).getDistance()
                                                                if (dist > 0f && prevDist > 0f) {
                                                                    pinchAccumulatedScale *= (dist / prevDist)
                                                                    if (pinchAccumulatedScale > 1.5f) {
                                                                        gridColumns = (gridColumns - 1).coerceAtLeast(2)
                                                                        pinchAccumulatedScale = 1f
                                                                    } else if (pinchAccumulatedScale < 0.67f) {
                                                                        gridColumns = (gridColumns + 1).coerceAtMost(6)
                                                                        pinchAccumulatedScale = 1f
                                                                    }
                                                                }
                                                                prevDist = dist
                                                            } else {
                                                                tracking = false
                                                                pinchAccumulatedScale = 1f
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                LazyVerticalGrid(
                                    state          = gridState,
                                    columns        = GridCells.Fixed(gridColumns),
                                    modifier       = Modifier
                                        .fillMaxSize()
                                        .padding(end = 52.sdp),
                                    contentPadding = PaddingValues(
                                        start  = 2.sdp,
                                        top    = 2.sdp,
                                        end    = 2.sdp,
                                        bottom = if (pendingGallery.isNotEmpty()) 70.sdp else 2.sdp
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(2.sdp),
                                    verticalArrangement   = Arrangement.spacedBy(2.sdp)
                                ) {
                                    items(galleryThumbs, key = { it.id }) { thumb ->
                                        val isSelected = thumb in pendingGallery
                                        val selIdx     = if (isSelected) pendingGallery.toList().indexOf(thumb) + 1 else 0
                                        GalleryGridItem(
                                            thumb        = thumb,
                                            galleryLoader = galleryLoader,
                                            isSelected   = isSelected,
                                            selNumber    = selIdx,
                                            onClick      = {
                                                pendingGallery =
                                                    if (isSelected) pendingGallery - thumb
                                                    else pendingGallery + thumb
                                            }
                                        )
                                    }
                                }

                                DateScrubber(
                                    sections    = gallerySections,
                                    totalItems  = galleryTotal.coerceAtLeast(galleryThumbs.size),
                                    loadedItems = galleryThumbs.size,
                                    gridState   = gridState,
                                    columns     = gridColumns,
                                    modifier    = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                )
                                }
                            }
                        }
                    }
                }

                if (pendingGallery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                            .padding(horizontal = 16.sdp, vertical = 10.sdp)
                    ) {
                        Button(
                            onClick  = {
                                pendingGallery.forEach {
                                    attachments = attachments + AttachItem.FromGallery(it)
                                }
                                pendingGallery  = emptySet()
                                showAttachSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.sdp))
                            Spacer(Modifier.width(8.sdp))
                            Text(s.attachNPhotos(pendingGallery.size))
                        }
                    }
                }
            }
        }
    }

    if (showMuteDialog) {
        AlertDialog(
            onDismissRequest = { showMuteDialog = false },
            title = { Text(s.muteNotifications) },
            text = {
                Column {
                    listOf(s.mute1Hour, s.mute8Hours, s.mute24Hours, s.muteForever).forEach { opt ->
                        TextButton(
                            onClick = { showMuteDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(opt, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMuteDialog = false }) { Text(s.cancel) } }
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(s.deleteChatTitle) },
            text  = { Text(s.deleteChatMessage) },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(s.deleteForAll) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(s.cancel) } }
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(s.clearHistory) },
            text  = { Text(s.clearHistoryMessage) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearMessages(); showClearDialog = false }) {
                    Text(s.onlyForMe)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showClearDialog = false }) { Text(s.cancel) }
                    TextButton(
                        onClick = { viewModel.clearMessages(); showClearDialog = false },
                        colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(s.forAll) }
                }
            }
        )
    }
    if (showBlockConfirm && !isGroupChat) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(s.blockConfirmTitle(chatName)) },
            text  = { Text(s.blockConfirmMessage(chatName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBlockConfirm = false
                        if (isPeerBlocked) viewModel.unblockPeer() else viewModel.blockPeer()
                    },
                    colors = if (!isPeerBlocked) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                             else ButtonDefaults.textButtonColors()
                ) { Text(if (isPeerBlocked) s.unblockUser else s.blockUser) }
            },
            dismissButton = { TextButton(onClick = { showBlockConfirm = false }) { Text(s.cancel) } }
        )
    }
    if (showFullScreenAvatar) {
        AlertDialog(
            onDismissRequest = { showFullScreenAvatar = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxSize().background(Color.Black),
            title = null,
            text = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier.size(240.sdp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(chatName.take(1).uppercase(), color = Color.White, fontSize = 100.ssp)
                    }

                    IconButton(
                        onClick = { showFullScreenAvatar = false },
                        modifier = Modifier.align(Alignment.TopStart).padding(16.sdp).statusBarsPadding()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (messagesToDelete != null) {
        AlertDialog(
            onDismissRequest = { messagesToDelete = null },
            title = { Text(s.deleteMessageTitle) },
            text = { Text(s.deleteMessageText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        messagesToDelete?.forEach { viewModel.deleteMessage(it) }
                        messagesToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(s.deleteForAll) }
            },
            dismissButton = { TextButton(onClick = { messagesToDelete = null }) { Text(s.cancel) } }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (isSearchMode) isSearchMode = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProfileClick() }
                            .padding(end = 16.sdp)
                    ) {
                        Box(modifier = Modifier.clickable { showFullScreenAvatar = true }) {
                            AvatarImage(
                                mediaId = peerAvatarMediaId,
                                size = 36.sdp,
                                fallbackLetter = chatName.take(1).uppercase(),
                                backgroundColor = topBarTextColor.copy(alpha = 0.2f),
                                textColor = topBarTextColor,
                                textStyle = TextStyle(fontSize = 16.ssp)
                            )
                        }
                        Spacer(Modifier.width(10.sdp))
                        Text(chatName, color = topBarTextColor)
                    }
                },
                actions = {
                    if (!isSearchMode) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, null, tint = topBarTextColor)
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text(s.search) },           leadingIcon = { Icon(Icons.Default.Search, null) },           onClick = { showMenu = false; isSearchMode = true })
                                DropdownMenuItem(text = { Text(s.notifications) },    leadingIcon = { Icon(Icons.Default.NotificationsOff, null) },  onClick = { showMenu = false; showMuteDialog = true })
                                DropdownMenuItem(text = { Text(s.changeWallpaper) },  leadingIcon = { Icon(Icons.Default.Wallpaper, null) },         onClick = { showMenu = false })
                                if (!isGroupChat) {
                                    DropdownMenuItem(
                                        text = { Text(if (isPeerBlocked) s.unblockUser else s.blockUser, color = if (!isPeerBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                                        leadingIcon = { Icon(Icons.Default.Block, null, tint = if (!isPeerBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                                        onClick = { showMenu = false; showBlockConfirm = true }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(s.clearHistory) }, leadingIcon = { Icon(Icons.Default.CleaningServices, null) }, onClick = { showMenu = false; showClearDialog = true })
                                DropdownMenuItem(
                                    text = { Text(s.deleteChat, color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showMenu = false; showDeleteDialog = true }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.sdp,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .consumeWindowInsets(WindowInsets.navigationBars.union(WindowInsets.ime))
            ) {
                if (isPeerBlocked && !isGroupChat) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.sdp, vertical = 12.sdp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.sdp))
                            Spacer(Modifier.width(8.sdp))
                            Text(
                                s.userBlockedBanner,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { viewModel.unblockPeer() }) {
                            Text(s.unblockUser, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (isMlsBroken) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.sdp, vertical = 14.sdp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Block,
                            null,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.sdp)
                        )
                        Spacer(Modifier.width(8.sdp))
                        Text(
                            s.mlsChatBrokenBanner,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (isBlockedByPeer && !isGroupChat) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.sdp, vertical = 14.sdp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Block,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.sdp)
                        )
                        Spacer(Modifier.width(8.sdp))
                        Text(
                            s.cannotMessageBlockedByPeer,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                Column {
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(8.sdp),
                            horizontalArrangement = Arrangement.spacedBy(8.sdp)
                        ) {
                            items(attachments) { item -> AttachmentThumbnail(item, galleryLoader) { attachments = attachments - item } }
                        }
                        HorizontalDivider()
                    }

                    if (replyingTo != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 12.sdp, vertical = 8.sdp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.sdp)
                            )
                            Spacer(Modifier.width(8.sdp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    s.reply,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = when {
                                        replyingTo!!.type == "voice" -> s.voiceMessage
                                        replyingTo!!.type == "file" -> "\uD83D\uDCCE " + (replyingTo!!.fileName ?: s.file)
                                        replyingTo!!.type == "image" && replyingTo!!.text.isBlank() -> s.photo
                                        replyingTo!!.type == "image" -> replyingTo!!.text.take(100)
                                        else -> replyingTo!!.text.take(100)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.clearReply() },
                                modifier = Modifier.size(24.sdp)
                            ) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.sdp))
                            }
                        }
                        HorizontalDivider()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.sdp, vertical = 6.sdp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (recordState != ChatViewModel.RecordState.IDLE) {
                            Row(
                                modifier = Modifier.weight(1f).height(48.sdp).padding(horizontal = 8.sdp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val alpha by animateFloatAsState(
                                    targetValue = if (recordState == ChatViewModel.RecordState.PAUSED) 0.3f else 1f,
                                    animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse)
                                )
                                Box(modifier = Modifier.size(10.sdp).clip(CircleShape).background(Color.Red.copy(alpha = alpha)))
                                Spacer(modifier = Modifier.width(8.sdp))

                                val sec = (recordDuration / 1000).toInt()
                                Text("${sec / 60}:${(sec % 60).toString().padStart(2, '0')}", fontSize = 16.ssp)
                                Spacer(modifier = Modifier.width(16.sdp))

                                if (recordState == ChatViewModel.RecordState.HOLDING) {
                                    Text(s.swipeToCancel, color = Color.Gray, fontSize = 14.ssp)
                                } else {
                                    VoiceWaveform(
                                        amplitudes = recordAmps,
                                        progress = 1f,
                                        modifier = Modifier.weight(1f).height(30.sdp),
                                        playedColor = MaterialTheme.colorScheme.primary,
                                        unplayedColor = Color.Gray.copy(alpha = 0.3f)
                                    )

                                    IconButton(onClick = { viewModel.cancelVoiceRecording() }, modifier = Modifier.size(32.sdp)) {
                                        Icon(Icons.Default.Delete, null, tint = Color.Gray)
                                    }
                                    IconButton(
                                        onClick = { if (recordState == ChatViewModel.RecordState.PAUSED) viewModel.resumeVoiceRecording() else viewModel.pauseVoiceRecording() },
                                        modifier = Modifier.size(32.sdp)
                                    ) {
                                        Icon(if (recordState == ChatViewModel.RecordState.PAUSED) Icons.Default.Mic else Icons.Default.Pause, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        } else {
                            IconButton(onClick = { }) { Icon(Icons.Default.SentimentSatisfied, null, tint = Color.Gray) }
                            OutlinedTextField(
                                value = inputText, onValueChange = { viewModel.updateInput(it) },
                                modifier = Modifier.weight(1f).focusRequester(inputFocusRequester), placeholder = { Text(s.messagePlaceholder) },
                                shape = RoundedCornerShape(24.sdp), maxLines = 5
                            )
                            IconButton(onClick = { galleryLoader.requestPermission(); showAttachSheet = true }) {
                                Icon(Icons.Default.AttachFile, null, tint = Color.Gray, modifier = Modifier.rotate(45f))
                            }
                        }

                        val isEmpty = inputText.trim().isEmpty() && attachments.isEmpty()

                        val sendButtonBg = when {
                            recordState != ChatViewModel.RecordState.IDLE -> Color.Red
                            !isEmpty -> MaterialTheme.colorScheme.primary
                            else -> Color.Transparent
                        }

                        val buttonModifier = Modifier
                            .size(48.sdp)
                            .clip(CircleShape)
                            .background(sendButtonBg)
                            .then(
                                when {
                                    recordState == ChatViewModel.RecordState.LOCKED || recordState == ChatViewModel.RecordState.PAUSED -> {
                                        Modifier.clickable { viewModel.stopAndSendVoiceMessage() }
                                    }
                                    !isEmpty -> {
                                        Modifier.clickable {
                                            val toSend = attachments
                                            attachments = emptyList()
                                            viewModel.sendMessage(toSend)
                                        }
                                    }
                                    else -> {
                                        Modifier.pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown()

                                                if (!viewModel.audioRecorder.hasPermission()) {
                                                    viewModel.audioRecorder.requestPermission()
                                                    var event = awaitPointerEvent()
                                                    while (event.changes.any { it.pressed }) { event = awaitPointerEvent() }
                                                    return@awaitEachGesture
                                                }

                                                if (viewModel.recordState.value == ChatViewModel.RecordState.IDLE) {
                                                    viewModel.startVoiceRecording()
                                                }

                                                var offsetX = 0f
                                                var offsetY = 0f
                                                var lockedOrCanceled = false

                                                do {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull()
                                                    if (change != null && change.pressed) {
                                                        val drag = change.position - change.previousPosition
                                                        offsetX += drag.x
                                                        offsetY += drag.y

                                                        if (offsetY < -200f && viewModel.recordState.value == ChatViewModel.RecordState.HOLDING) {
                                                            viewModel.lockVoiceRecording()
                                                            lockedOrCanceled = true
                                                        } else if (offsetX < -200f && viewModel.recordState.value == ChatViewModel.RecordState.HOLDING) {
                                                            viewModel.cancelVoiceRecording()
                                                            lockedOrCanceled = true
                                                        }
                                                    }
                                                } while (event.changes.any { it.pressed } && !lockedOrCanceled)

                                                if (!lockedOrCanceled && viewModel.recordState.value == ChatViewModel.RecordState.HOLDING) {
                                                    viewModel.stopAndSendVoiceMessage()
                                                }
                                            }
                                        }
                                    }
                                }
                            )

                        Box(
                            modifier = buttonModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            if (recordState == ChatViewModel.RecordState.LOCKED || recordState == ChatViewModel.RecordState.PAUSED) {
                                Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
                            } else if (isEmpty) {
                                Icon(Icons.Default.Mic, null, tint = if (recordState == ChatViewModel.RecordState.HOLDING) Color.White else Color.Gray)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
                            }
                        }
                    }
                }
            }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            VoicePlaybackBar(
                state = audioPlaybackState,
                onTogglePlayPause = { globalAudioPlayer.togglePlayPause() },
                onSeek = { globalAudioPlayer.seekTo(it) },
                onClose = { globalAudioPlayer.stop() },
                accentColor = topBarColor
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(chatBgColor)
            ) {
            val chatBgBitmap = rememberAsyncImageBitmap(
                bytes = chatBgImage,
                cacheKey = if (chatBgImage != null) "chatbg" else null
            )
            if (chatBgBitmap != null) {
                Image(
                    bitmap = chatBgBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            LazyColumn(
                state          = listState,
                modifier       = Modifier.fillMaxSize().padding(horizontal = 12.sdp),
                contentPadding = PaddingValues(top = 12.sdp, bottom = 16.sdp),
                verticalArrangement = Arrangement.spacedBy(8.sdp, Alignment.Bottom)
            ) {
                items(chatItems.size, key = { chatItems[it].stableKey }) { index ->
                    val chatItem = chatItems[index]

                    when (chatItem) {
                        is ChatItem.Single -> {
                            val message = chatItem.message
                            if (message.text.isBlank() && message.mediaId == null && message.localPreviewBytes == null) return@items
                            val senderId = messageSenders[message.serverId]
                            val profile = memberProfiles[senderId]
                            val replyToServerId = replyContext[message.serverId]
                            val replyToMessage = replyToServerId?.let { rid -> messages.find { it.serverId == rid } }
                            val replyToSenderName = replyToMessage?.let { rm ->
                                val replySenderId = messageSenders[rm.serverId]
                                replySenderId?.let { memberProfiles[it]?.username }
                            }

                            if (message.type == "system") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.sdp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                RoundedCornerShape(12.sdp)
                                            )
                                            .padding(horizontal = 12.sdp, vertical = 4.sdp)
                                    )
                                }
                                return@items
                            }

                            Box {
                                if (isGroupChat && !message.isOutgoing) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 2.sdp)
                                            .pointerInput(message.id) {
                                                detectTapGestures(
                                                    onLongPress = { offset ->
                                                        with(density) {
                                                            contextMenuOffset = DpOffset(
                                                                x = offset.x.toDp(),
                                                                y = offset.y.toDp()
                                                            )
                                                        }
                                                        contextMenuMessage = message
                                                    }
                                                )
                                            },
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.sdp)
                                        ) {
                                            AvatarImage(
                                                mediaId = profile?.avatarMediaId,
                                                size = 36.sdp,
                                                fallbackLetter = profile?.username?.take(1)?.uppercase() ?: "?",
                                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                textStyle = TextStyle(fontSize = 14.ssp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.sdp))

                                        Column(horizontalAlignment = Alignment.Start) {
                                            Text(
                                                text = profile?.username ?: s.member,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 4.sdp, bottom = 2.sdp)
                                            )

                                            SwipeToReplyContainer(onReply = { viewModel.setReplyTo(message) }) {
                                                MessageBubble(
                                                    message          = message,
                                                    myBubbleColor    = myBubbleColor,
                                                    theirBubbleColor = theirBubbleColor,
                                                    myBubbleImage    = myBubbleImage,
                                                    theirBubbleImage = theirBubbleImage,
                                                    searchQuery      = searchQuery,
                                                    isCurrentMatch   = message.id == currentMatchMsgId,
                                                    mediaCache       = mediaCache,
                                                    onLoadMedia      = { id, meta -> viewModel.loadMedia(id, meta) },
                                                    globalAudioPlayer = globalAudioPlayer,
                                                    chatName         = chatName,
                                                    replyToMessage   = replyToMessage,
                                                    replyToSenderName = replyToSenderName,
                                                    onReplyClick     = replyToMessage?.let { replied ->
                                                        {
                                                            val idx = chatItems.indexOfFirst { item ->
                                                                item.allMessages.any { it.serverId == replied.serverId }
                                                            }
                                                            if (idx >= 0) {
                                                                coroutineScope.launch { listState.animateScrollToItem(idx) }
                                                            }
                                                        }
                                                    },
                                                    downloadingFiles = downloadingFiles,
                                                    onFileTap        = { viewModel.onFileBubbleTap(it) },
                                                    onPhotoClick     = onPhotoClick,
                                                    isHighlighted    = (message.id == highlightedMessageId)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerInput(message.id) {
                                                detectTapGestures(
                                                    onLongPress = { offset ->
                                                        with(density) {
                                                            contextMenuOffset = DpOffset(
                                                                x = offset.x.toDp(),
                                                                y = offset.y.toDp()
                                                            )
                                                        }
                                                        contextMenuMessage = message
                                                    }
                                                )
                                            }
                                    ) {
                                        SwipeToReplyContainer(onReply = { viewModel.setReplyTo(message) }) {
                                            MessageBubble(
                                                message          = message,
                                                myBubbleColor    = myBubbleColor,
                                                theirBubbleColor = theirBubbleColor,
                                                myBubbleImage    = myBubbleImage,
                                                theirBubbleImage = theirBubbleImage,
                                                searchQuery      = searchQuery,
                                                isCurrentMatch   = message.id == currentMatchMsgId,
                                                mediaCache       = mediaCache,
                                                onLoadMedia      = { id, meta -> viewModel.loadMedia(id, meta) },
                                                globalAudioPlayer = globalAudioPlayer,
                                                chatName         = chatName,
                                                replyToMessage   = replyToMessage,
                                                replyToSenderName = replyToSenderName,
                                                onReplyClick     = replyToMessage?.let { replied ->
                                                    {
                                                        val idx = chatItems.indexOfFirst { item ->
                                                            item.allMessages.any { it.serverId == replied.serverId }
                                                        }
                                                        if (idx >= 0) {
                                                            coroutineScope.launch { listState.animateScrollToItem(idx) }
                                                        }
                                                    }
                                                },
                                                downloadingFiles = downloadingFiles,
                                                onFileTap        = { viewModel.onFileBubbleTap(it) },
                                                onPhotoClick     = onPhotoClick,
                                                isHighlighted    = (message.id == highlightedMessageId)
                                            )
                                        }
                                    }
                                }

                                DropdownMenu(
                                    expanded = contextMenuMessage?.id == message.id,
                                    onDismissRequest = { contextMenuMessage = null },
                                    offset = contextMenuOffset
                                ) {
                                    val isImageMsg = message.type == "image"
                                    val hasText = message.text.isNotBlank() && message.type != "voice"

                                    if (isImageMsg) {
                                        DropdownMenuItem(
                                            text = { Text(s.saveToGallery) },
                                            leadingIcon = { Icon(Icons.Default.Download, null) },
                                            onClick = {
                                                val mediaId = message.mediaId
                                                contextMenuMessage = null
                                                if (mediaId != null) {
                                                    val bytes = mediaCache[mediaId]
                                                    if (bytes != null) {
                                                        coroutineScope.launch {
                                                            saveImageToGallery(bytes, "memegram_$mediaId.jpg")
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }

                                    if (hasText) {
                                        DropdownMenuItem(
                                            text = { Text(s.copyText) },
                                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(message.text))
                                                contextMenuMessage = null
                                            }
                                        )

                                        if (message.isTranslated) {
                                            DropdownMenuItem(
                                                text = { Text(s.showOriginal) },
                                                leadingIcon = { Icon(Icons.Default.Translate, null) },
                                                onClick = {
                                                    viewModel.revertTranslation(message)
                                                    contextMenuMessage = null
                                                }
                                            )
                                        } else if (message.translatedText != null) {
                                            DropdownMenuItem(
                                                text = { Text(s.showTranslation) },
                                                leadingIcon = { Icon(Icons.Default.Translate, null) },
                                                onClick = {
                                                    viewModel.showCachedTranslation(message)
                                                    contextMenuMessage = null
                                                }
                                            )
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text(s.translate) },
                                                leadingIcon = { Icon(Icons.Default.Translate, null) },
                                                onClick = {
                                                    viewModel.translateMessage(message)
                                                    contextMenuMessage = null
                                                }
                                            )
                                        }
                                    }

                                    DropdownMenuItem(
                                        text = { Text(s.reply) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                                        onClick = {
                                            viewModel.setReplyTo(message)
                                            contextMenuMessage = null
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text(s.deleteForAll, color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            messagesToDelete = listOf(message)
                                            contextMenuMessage = null
                                        }
                                    )
                                }
                            }
                        }

                        is ChatItem.Album -> {
                            val albumMessages = chatItem.messages
                            val firstMessage = albumMessages.first()
                            val isOut = firstMessage.isOutgoing
                            val senderId = messageSenders[firstMessage.serverId]
                            val profile = memberProfiles[senderId]

                            var showAlbumContextMenu by remember { mutableStateOf(false) }
                            var albumContextOffset by remember { mutableStateOf(DpOffset.Zero) }

                            Box {
                                if (isGroupChat && !isOut) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 2.sdp)
                                            .pointerInput(chatItem.groupId) {
                                                detectTapGestures(
                                                    onLongPress = { offset ->
                                                        with(density) {
                                                            albumContextOffset = DpOffset(
                                                                x = offset.x.toDp(),
                                                                y = offset.y.toDp()
                                                            )
                                                        }
                                                        showAlbumContextMenu = true
                                                    }
                                                )
                                            },
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        Box(modifier = Modifier.size(36.sdp)) {
                                            AvatarImage(
                                                mediaId = profile?.avatarMediaId,
                                                size = 36.sdp,
                                                fallbackLetter = profile?.username?.take(1)?.uppercase() ?: "?",
                                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                textStyle = TextStyle(fontSize = 14.ssp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.sdp))

                                        Column(horizontalAlignment = Alignment.Start) {
                                            Text(
                                                text = profile?.username ?: s.member,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 4.sdp, bottom = 2.sdp)
                                            )

                                            SwipeToReplyContainer(onReply = {
                                                albumMessages.firstOrNull()?.let { viewModel.setReplyTo(it) }
                                            }) {
                                                AlbumBubble(
                                                    albumMessages = albumMessages,
                                                    isOutgoing = isOut,
                                                    myBubbleColor = myBubbleColor,
                                                    theirBubbleColor = theirBubbleColor,
                                                    myBubbleImage = myBubbleImage,
                                                    theirBubbleImage = theirBubbleImage,
                                                    mediaCache = mediaCache,
                                                    onLoadMedia = { id, meta -> viewModel.loadMedia(id, meta) },
                                                    downloadingFiles = downloadingFiles,
                                                    onFileTap = { viewModel.onFileBubbleTap(it) },
                                                    searchQuery = searchQuery,
                                                    isCurrentMatch = albumMessages.any { it.id == currentMatchMsgId },
                                                    onPhotoClick = onPhotoClick,
                                                    isHighlighted = albumMessages.any { it.id == highlightedMessageId }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerInput(chatItem.groupId) {
                                                detectTapGestures(
                                                    onLongPress = { offset ->
                                                        with(density) {
                                                            albumContextOffset = DpOffset(
                                                                x = offset.x.toDp(),
                                                                y = offset.y.toDp()
                                                            )
                                                        }
                                                        showAlbumContextMenu = true
                                                    }
                                                )
                                            }
                                    ) {
                                        SwipeToReplyContainer(onReply = {
                                            albumMessages.firstOrNull()?.let { viewModel.setReplyTo(it) }
                                        }) {
                                            AlbumBubble(
                                                albumMessages = albumMessages,
                                                isOutgoing = isOut,
                                                myBubbleColor = myBubbleColor,
                                                theirBubbleColor = theirBubbleColor,
                                                myBubbleImage = myBubbleImage,
                                                theirBubbleImage = theirBubbleImage,
                                                mediaCache = mediaCache,
                                                onLoadMedia = { id, meta -> viewModel.loadMedia(id, meta) },
                                                downloadingFiles = downloadingFiles,
                                                onFileTap = { viewModel.onFileBubbleTap(it) },
                                                searchQuery = searchQuery,
                                                isCurrentMatch = albumMessages.any { it.id == currentMatchMsgId },
                                                onPhotoClick = onPhotoClick,
                                                isHighlighted = albumMessages.any { it.id == highlightedMessageId }
                                            )
                                        }
                                    }
                                }

                                DropdownMenu(
                                    expanded = showAlbumContextMenu,
                                    onDismissRequest = { showAlbumContextMenu = false },
                                    offset = albumContextOffset
                                ) {
                                    val photoInAlbum = albumMessages.filter { it.type == "image" }
                                    if (photoInAlbum.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text(s.saveAllNPhotos(photoInAlbum.size)) },
                                            leadingIcon = { Icon(Icons.Default.Download, null) },
                                            onClick = {
                                                showAlbumContextMenu = false
                                                coroutineScope.launch {
                                                    photoInAlbum.forEach { msg ->
                                                        val mid = msg.mediaId
                                                        if (mid != null) {
                                                            val bytes = mediaCache[mid]
                                                            if (bytes != null) {
                                                                saveImageToGallery(bytes, "memegram_$mid.jpg")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }

                                    val captionMsg = albumMessages.firstOrNull { it.text.isNotBlank() }
                                    if (captionMsg != null) {
                                        DropdownMenuItem(
                                            text = { Text(s.copyText) },
                                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(captionMsg.text))
                                                showAlbumContextMenu = false
                                            }
                                        )
                                    }

                                    DropdownMenuItem(
                                        text = { Text(s.reply) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                                        onClick = {
                                            viewModel.setReplyTo(firstMessage)
                                            showAlbumContextMenu = false
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text(s.deleteForAll, color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            messagesToDelete = albumMessages
                                            showAlbumContextMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
fun GalleryGridItem(
    thumb: GalleryThumb,
    galleryLoader: com.example.memegram.data.gallery.GalleryLoader,
    isSelected: Boolean,
    selNumber: Int,
    onClick: () -> Unit
) {
    val bytes: ByteArray? by produceState<ByteArray?>(
        initialValue = if (thumb.bytes.isNotEmpty()) thumb.bytes else null,
        thumb.id
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                runCatching { galleryLoader.loadThumbBytes(thumb.id) }.getOrNull()
            }
        }
    }
    val bitmap = rememberAsyncImageBitmap(
        bytes    = bytes,
        cacheKey = "gallery:${thumb.id}"
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.sdp))
            .clickable { onClick() }
    ) {
        if (bitmap != null) {
            Image(
                bitmap           = bitmap,
                contentDescription = null,
                modifier         = Modifier.fillMaxSize(),
                contentScale     = ContentScale.Crop
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }

        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }

        Box(
            modifier = Modifier
                .padding(5.sdp)
                .size(24.sdp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else Color.Black.copy(alpha = 0.35f)
                )
                .border(1.5.sdp, Color.White, CircleShape)
                .align(Alignment.TopEnd),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text(selNumber.toString(), color = Color.White, fontSize = 11.ssp)
            }
        }
    }
}

@Composable
fun AttachmentThumbnail(
    item: AttachItem,
    galleryLoader: com.example.memegram.data.gallery.GalleryLoader,
    onRemove: () -> Unit
) {
    val bytes by produceState<ByteArray?>(initialValue = null, item) {
        value = runCatching {
            withContext(Dispatchers.Default) {
                when (item) {
                    is AttachItem.FromPicker  -> item.file.readBytes()
                    is AttachItem.FromGallery ->
                        if (item.thumb.bytes.isNotEmpty()) item.thumb.bytes
                        else galleryLoader.loadThumbBytes(item.thumb.id)
                    is AttachItem.FromBytes   -> item.bytes
                }
            }
        }.getOrNull()
    }
    val bitmap = rememberAsyncImageBitmap(bytes = bytes)

    Box(
        modifier = Modifier
            .size(72.sdp)
            .clip(RoundedCornerShape(8.sdp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (bitmap != null) {
            Image(
                bitmap           = bitmap!!,
                contentDescription = null,
                modifier         = Modifier.fillMaxSize(),
                contentScale     = ContentScale.Crop
            )
        } else {
            Column(
                modifier            = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(28.sdp), tint = MaterialTheme.colorScheme.primary)
                Text(item.name, fontSize = 9.ssp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.sdp))
            }
        }
        Box(
            modifier = Modifier
                .size(20.sdp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .align(Alignment.TopEnd)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.sdp))
        }
    }
}

fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return try {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val local   = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) { "" }
}

@Composable
fun MessageBubble(
    message: Message,
    myBubbleColor: Color,
    theirBubbleColor: Color,
    myBubbleImage: ByteArray? = null,
    theirBubbleImage: ByteArray? = null,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    mediaCache: Map<String, ByteArray> = emptyMap(),
    onLoadMedia: (String, String?) -> Unit = { _, _ -> },
    globalAudioPlayer: GlobalAudioPlayer? = null,
    chatName: String = "",
    replyToMessage: Message? = null,
    replyToSenderName: String? = null,
    onReplyClick: (() -> Unit)? = null,
    downloadingFiles: Set<String> = emptySet(),
    onFileTap: (Message) -> Unit = {},
    onPhotoClick: (Int) -> Unit = {},
    isHighlighted: Boolean = false,
) {
    val isOut       = message.isOutgoing
    val s = LocalStrings.current
    val bubbleColor = if (isCurrentMatch) MaterialTheme.colorScheme.tertiary
    else if (isOut) myBubbleColor else theirBubbleColor
    val bubbleImageBytes = if (isCurrentMatch) null
    else if (isOut) myBubbleImage else theirBubbleImage
    val bubbleImageBitmap = rememberAsyncImageBitmap(
        bytes = bubbleImageBytes,
        cacheKey = if (bubbleImageBytes != null) "bubble:${if (isOut) "out" else "in"}" else null
    )
    val textColor   = if (bubbleImageBitmap != null) Color.White
    else if (bubbleColor.luminance() > 0.5f) Color.Black else Color.White
    val timeText    = remember(message.timestamp) { formatMessageTime(message.timestamp) }
    val timeColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    val cachedBytes = message.mediaId?.let { mediaCache[it] }
    val bytesForDecode = if (message.type == "image") cachedBytes ?: message.localPreviewBytes else null
    val localBitmap = rememberAsyncImageBitmap(
        bytes = bytesForDecode,
        cacheKey = message.mediaId?.let { "msg:$it" },
        maxDimension = CHAT_BUBBLE_MAX_IMAGE_DIM,
    )
    val mediaId = message.mediaId
    val encMeta = message.encryptionMetadata
    LaunchedEffect(mediaId) {
        if (mediaId != null && cachedBytes == null && message.localPreviewBytes == null) {
            onLoadMedia(mediaId, encMeta)
        }
    }
    val isFileMsg  = message.type == "file"
    val isVoiceMsg = message.type == "voice"
    val isImageMsg = !isFileMsg && !isVoiceMsg && (message.type == "image" || localBitmap != null)
    val hasText    = message.text.isNotBlank() && !isVoiceMsg
    val isPhotoOnly = isImageMsg && !hasText

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment     = Alignment.Bottom
    ) {
        if (isOut && timeText.isNotEmpty()) {
            Text(timeText, color = timeColor, fontSize = 11.ssp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(end = 4.sdp, bottom = 4.sdp))
            if (message.status == MessageStatus.SENT || message.status == MessageStatus.READ) {
                Text(
                    text = if (message.status == MessageStatus.READ) "✓✓" else "✓",
                    color = timeColor,
                    fontSize = 11.ssp,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(end = 6.sdp, bottom = 4.sdp)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 280.sdp)
                .clip(RoundedCornerShape(
                    topStart    = 16.sdp, topEnd = 16.sdp,
                    bottomStart = if (isOut) 16.sdp else 4.sdp,
                    bottomEnd   = if (isOut) 4.sdp else 16.sdp
                ))
                .background(bubbleColor)
        ) {
            if (bubbleImageBitmap != null) {
                Image(
                    bitmap = bubbleImageBitmap,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier.padding(
                    horizontal = if (isImageMsg && !hasText) 0.sdp else 12.sdp,
                    vertical   = if (isImageMsg && !hasText) 0.sdp else 8.sdp
                )
            ) {
            Column {
                if (replyToMessage != null) {
                    Box(
                        modifier = Modifier
                            .padding(
                                start = if (isImageMsg && !hasText) 8.sdp else 0.sdp,
                                end = if (isImageMsg && !hasText) 8.sdp else 0.sdp,
                                top = if (isImageMsg && !hasText) 8.sdp else 0.sdp,
                                bottom = 4.sdp
                            )
                            .clip(RoundedCornerShape(8.sdp))
                            .background(textColor.copy(alpha = 0.1f))
                            .then(
                                if (onReplyClick != null) Modifier.clickable { onReplyClick() }
                                else Modifier
                            )
                            .padding(horizontal = 8.sdp, vertical = 6.sdp)
                    ) {
                        Column {
                            Text(
                                text = if (replyToMessage.isOutgoing) s.you else (replyToSenderName ?: s.interlocutor),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (bubbleColor.luminance() > 0.5f)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.inversePrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    replyToMessage.type == "voice" -> s.voiceMessage
                                    replyToMessage.type == "file" -> "\uD83D\uDCCE " + (replyToMessage.fileName ?: s.file)
                                    replyToMessage.type == "image" && replyToMessage.text.isBlank() -> s.photo
                                    replyToMessage.type == "image" -> replyToMessage.text.take(50)
                                    else -> replyToMessage.text.take(100)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = textColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                if (message.type == "voice") {
                    val parts = message.text.split("|")
                    val durationMs = parts[0].toLongOrNull() ?: 0L
                    val waveformStr = if (parts.size > 1) parts[1] else ""
                    val parsedAmps = waveformStr.map { it.digitToIntOrNull() ?: 0 }

                    val gapState = globalAudioPlayer?.state?.collectAsState()
                    val gapValue = gapState?.value
                    val isThisActive = gapValue != null &&
                        gapValue.mediaId == message.mediaId &&
                        gapValue.status != GlobalAudioPlayer.PlaybackStatus.IDLE
                    val isPlaying = isThisActive && gapValue?.status == GlobalAudioPlayer.PlaybackStatus.PLAYING
                    val isPaused = isThisActive && gapValue?.status == GlobalAudioPlayer.PlaybackStatus.PAUSED
                    val currentProgress = if (isThisActive) (gapValue?.progress ?: 0f) else 0f

                    val totalSeconds = (durationMs / 1000).toInt()
                    val totalText = "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"

                    val currentSeconds = ((durationMs * currentProgress) / 1000).toInt()
                    val currentText = "${currentSeconds / 60}:${(currentSeconds % 60).toString().padStart(2, '0')}"

                    val displayTime = if (currentProgress > 0f && (isPlaying || isPaused)) {
                        "$currentText / $totalText"
                    } else {
                        totalText
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.sdp, vertical = 8.sdp).width(200.sdp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.sdp)
                                .clip(CircleShape)
                                .background(textColor.copy(alpha = 0.15f))
                                .clickable {
                                    if (isPlaying) {
                                        globalAudioPlayer?.pause()
                                    } else if (isPaused) {
                                        globalAudioPlayer?.resume()
                                    } else {
                                        val bytes = cachedBytes
                                        val mid = message.mediaId
                                        if (bytes != null && mid != null && globalAudioPlayer != null) {
                                            globalAudioPlayer.play(
                                                bytes = bytes,
                                                mediaId = mid,
                                                chatName = chatName,
                                                durationMs = durationMs,
                                                waveform = parsedAmps
                                            )
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (cachedBytes == null) {
                                CircularProgressIndicator(modifier = Modifier.size(20.sdp), color = textColor, strokeWidth = 2.sdp)
                            } else {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = textColor)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.sdp))

                        Column(modifier = Modifier.weight(1f)) {
                            VoiceWaveform(
                                amplitudes = parsedAmps.ifEmpty { List(30) { 1 } },
                                progress = currentProgress,
                                modifier = Modifier.fillMaxWidth().height(24.sdp),
                                playedColor = textColor,
                                unplayedColor = textColor.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(4.sdp))
                            Text(displayTime, color = textColor.copy(alpha = 0.7f), fontSize = 11.ssp)
                        }
                    }
                } else if (isFileMsg) {
                    val mid = message.mediaId
                    val isDownloading = mid != null && mid in downloadingFiles
                    val hasLocal = !message.localFilePath.isNullOrBlank()
                    val isFailed = message.status == MessageStatus.FAILED
                    val sizeText = message.fileSize?.takeIf { it > 0 }?.let { formatSizeBytes(it) } ?: ""
                    val mime = message.fileMime ?: ""
                    val fileEmoji = when {
                        mime.startsWith("image/") -> "\uD83D\uDDBC"
                        mime.startsWith("video/") -> "\uD83C\uDFAC"
                        mime.startsWith("audio/") -> "\uD83C\uDFB5"
                        mime == "application/pdf" -> "\uD83D\uDCD5"
                        mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar") -> "\uD83D\uDDC4"
                        mime.contains("word") || mime.contains("document") -> "\uD83D\uDCC4"
                        mime.contains("sheet") || mime.contains("excel") -> "\uD83D\uDCCA"
                        mime.contains("presentation") || mime.contains("powerpoint") -> "\uD83D\uDCCA"
                        mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") -> "\uD83D\uDCDD"
                        else -> "\uD83D\uDCCE"
                    }
                    val errorColor = MaterialTheme.colorScheme.error
                    val iconBgColor = if (isFailed) errorColor.copy(alpha = 0.18f) else textColor.copy(alpha = 0.15f)
                    val fileNameColor = if (isFailed) errorColor else textColor
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .widthIn(min = 180.sdp, max = 210.sdp)
                            .clickable(enabled = !isDownloading && !isFailed) { onFileTap(message) }
                            .padding(vertical = 4.sdp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.sdp)
                                .clip(CircleShape)
                                .background(iconBgColor),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isFailed -> Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = errorColor
                                )
                                isDownloading -> CircularProgressIndicator(
                                    modifier = Modifier.size(22.sdp),
                                    color = textColor,
                                    strokeWidth = 2.sdp
                                )
                                !hasLocal && (message.status == MessageStatus.SENT || message.status == MessageStatus.READ) -> Icon(
                                    Icons.Default.Download,
                                    contentDescription = s.downloadFile,
                                    tint = textColor
                                )
                                else -> Text(fileEmoji, fontSize = 22.ssp)
                            }
                        }
                        Spacer(Modifier.width(10.sdp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = message.fileName ?: s.file,
                                color = fileNameColor,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (sizeText.isNotEmpty()) {
                                Text(
                                    text = sizeText,
                                    color = textColor.copy(alpha = 0.65f),
                                    fontSize = 11.ssp
                                )
                            }
                        }
                    }
                    if (hasText) Spacer(Modifier.height(6.sdp))
                } else if (isImageMsg) {
                    if (localBitmap != null) {
                        val ratio = (localBitmap.width.toFloat() / localBitmap.height.toFloat())
                            .takeIf { it.isFinite() && it > 0f } ?: 1f
                        Image(
                            bitmap             = localBitmap,
                            contentDescription = null,
                            contentScale       = ContentScale.Fit,
                            modifier           = Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(
                                    topStart    = 16.sdp, topEnd = 16.sdp,
                                    bottomStart = if (!hasText && isOut) 16.sdp else if (!hasText) 4.sdp else 0.sdp,
                                    bottomEnd   = if (!hasText && isOut) 4.sdp else if (!hasText) 16.sdp else 0.sdp
                                ))
                                .clickable { onPhotoClick(message.id) }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.sdp)
                                .background(bubbleColor.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (message.status == MessageStatus.SENDING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.sdp),
                                    color = textColor
                                )
                            } else {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.sdp),
                                    tint = textColor.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    if (hasText) Spacer(Modifier.height(6.sdp))
                }

                if (hasText) {
                    val textMod = if (isImageMsg) Modifier.padding(horizontal = 12.sdp, vertical = 8.sdp) else Modifier
                    val annotated = buildAnnotatedString {
                        if (searchQuery.isBlank()) {
                            append(message.text)
                        } else {
                            val lower = message.text.lowercase()
                            val lowerQ = searchQuery.lowercase()
                            var pos = 0
                            while (pos < message.text.length) {
                                val idx = lower.indexOf(lowerQ, pos)
                                if (idx == -1) { append(message.text.substring(pos)); break }
                                append(message.text.substring(pos, idx))
                                withStyle(SpanStyle(background = Color(0xFFFFD60A))) {
                                    append(message.text.substring(idx, idx + searchQuery.length))
                                }
                                pos = idx + searchQuery.length
                            }
                        }
                    }
                    Text(text = annotated, color = textColor,
                        style = MaterialTheme.typography.bodyLarge, modifier = textMod)
                    if (message.isTranslated && message.translatedFromLang != null) {
                        val indicator = com.example.memegram.translation.translationIndicator(message.translatedFromLang)
                        Text(
                            text = "$indicator ${s.translated}",
                            color = textColor.copy(alpha = 0.55f),
                            fontSize = 10.ssp,
                            modifier = if (isImageMsg) Modifier.padding(horizontal = 12.sdp) else Modifier
                        )
                    }
                }

                if (isOut && message.status != MessageStatus.SENT && message.status != MessageStatus.READ) {
                    val iconMod = if (isImageMsg)
                        Modifier.padding(end = 8.sdp, bottom = 4.sdp).align(Alignment.End)
                    else Modifier.align(Alignment.End)
                    Text(
                        text     = if (message.status == MessageStatus.SENDING) "⏳" else "❌",
                        fontSize = 11.ssp,
                        color    = textColor.copy(alpha = 0.6f),
                        modifier = iconMod
                    )
                }
            }
            }
            val highlightAlpha by animateFloatAsState(
                targetValue = if (isHighlighted) 0.35f else 0f,
                animationSpec = tween(durationMillis = 600),
                label = "bubbleHighlight",
            )
            if (highlightAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha))
                )
            }
        }

        if (!isOut && timeText.isNotEmpty()) {
            Text(timeText, color = timeColor, fontSize = 11.ssp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = 4.sdp, bottom = 4.sdp))
        }
    }
}

@Composable
fun VoiceWaveform(
    amplitudes: List<Int>,
    progress: Float,
    modifier: Modifier,
    playedColor: Color,
    unplayedColor: Color
) {
    val barWidthDp = 3.sdp
    val spacingDp = 2.sdp
    val minBarHeightDp = 4.sdp
    Canvas(modifier = modifier) {
        val barWidth = barWidthDp.toPx()
        val spacing = spacingDp.toPx()
        val maxBars = (size.width / (barWidth + spacing)).toInt()

        val displayAmps = if (amplitudes.size > maxBars) amplitudes.takeLast(maxBars) else amplitudes
        val startX = 0f

        displayAmps.forEachIndexed { index, amp ->
            val barHeight = maxOf(minBarHeightDp.toPx(), (amp / 9f) * size.height)
            val x = startX + index * (barWidth + spacing)
            val isPlayed = (index.toFloat() / displayAmps.size) <= progress

            drawRoundRect(
                color = if (isPlayed) playedColor else unplayedColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - barHeight) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

sealed class ChatItem {
    data class Single(val message: Message) : ChatItem()
    data class Album(val messages: List<Message>, val groupId: String) : ChatItem()

    val allMessages: List<Message>
        get() = when (this) {
            is Single -> listOf(message)
            is Album -> messages
        }

    val stableKey: Int
        get() = when (this) {
            is Single -> message.id
            is Album -> groupId.hashCode()
        }
}

fun groupMessages(messages: List<Message>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<ChatItem>()
    val buffer = mutableListOf<Message>()
    var currentGroupId: String? = null

    fun flushBuffer() {
        if (buffer.isEmpty()) return
        if (buffer.size == 1) {
            result += ChatItem.Single(buffer.first())
        } else {
            result += ChatItem.Album(buffer.toList(), currentGroupId!!)
        }
        buffer.clear()
        currentGroupId = null
    }

    for (msg in messages) {
        val gid = msg.groupId
        if (gid != null && (msg.type == "image" || msg.type == "file")) {
            if (gid == currentGroupId) {
                buffer += msg
            } else {
                flushBuffer()
                currentGroupId = gid
                buffer += msg
            }
        } else {
            flushBuffer()
            result += ChatItem.Single(msg)
        }
    }
    flushBuffer()
    return result
}

@Composable
fun AlbumBubble(
    albumMessages: List<Message>,
    isOutgoing: Boolean,
    myBubbleColor: Color,
    theirBubbleColor: Color,
    myBubbleImage: ByteArray? = null,
    theirBubbleImage: ByteArray? = null,
    mediaCache: Map<String, ByteArray> = emptyMap(),
    onLoadMedia: (String, String?) -> Unit = { _, _ -> },
    downloadingFiles: Set<String> = emptySet(),
    onFileTap: (Message) -> Unit = {},
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    onPhotoClick: (Int) -> Unit = {},
    isHighlighted: Boolean = false,
) {
    val s = LocalStrings.current
    val bubbleColor = if (isCurrentMatch) MaterialTheme.colorScheme.tertiary
    else if (isOutgoing) myBubbleColor else theirBubbleColor
    val bubbleImageBytes = if (isCurrentMatch) null
    else if (isOutgoing) myBubbleImage else theirBubbleImage
    val bubbleImageBitmap = rememberAsyncImageBitmap(
        bytes = bubbleImageBytes,
        cacheKey = if (bubbleImageBytes != null) "bubble:${if (isOutgoing) "out" else "in"}" else null
    )
    val textColor = if (bubbleImageBitmap != null) Color.White
    else if (bubbleColor.luminance() > 0.5f) Color.Black else Color.White
    val timeText = remember(albumMessages.lastOrNull()?.timestamp) {
        formatMessageTime(albumMessages.lastOrNull()?.timestamp ?: 0L)
    }
    val timeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    val caption = albumMessages.firstOrNull { it.text.isNotBlank() && it.status != MessageStatus.FAILED }?.text

    val photoMessages = albumMessages.filter { it.type == "image" }
    val fileMessages = albumMessages.filter { it.type == "file" }

    val photoBitmaps = photoMessages.map { msg ->
        val cachedBytes = msg.mediaId?.let { mediaCache[it] }
        val bytesForDecode = cachedBytes ?: msg.localPreviewBytes
        val bitmap = rememberAsyncImageBitmap(
            bytes = bytesForDecode,
            cacheKey = msg.mediaId?.let { "msg:$it" },
            maxDimension = ALBUM_CELL_MAX_IMAGE_DIM,
        )
        val mediaId = msg.mediaId
        val encMeta = msg.encryptionMetadata
        LaunchedEffect(mediaId) {
            if (mediaId != null && cachedBytes == null && msg.localPreviewBytes == null) {
                onLoadMedia(mediaId, encMeta)
            }
        }
        bitmap
    }

    val hasCaption = !caption.isNullOrBlank()
    val hasFiles = fileMessages.isNotEmpty()
    val hasPhotos = photoMessages.isNotEmpty()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (isOutgoing && timeText.isNotEmpty()) {
            Text(
                timeText, color = timeColor, fontSize = 11.ssp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(end = 4.sdp, bottom = 4.sdp)
            )
            val allRead = albumMessages.all { it.status == MessageStatus.READ }
            val allDelivered = albumMessages.all { it.status == MessageStatus.SENT || it.status == MessageStatus.READ }
            if (allDelivered) {
                Text(
                    text = if (allRead) "✓✓" else "✓",
                    color = timeColor,
                    fontSize = 11.ssp,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(end = 6.sdp, bottom = 4.sdp)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 280.sdp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.sdp, topEnd = 16.sdp,
                        bottomStart = if (isOutgoing) 16.sdp else 4.sdp,
                        bottomEnd = if (isOutgoing) 4.sdp else 16.sdp
                    )
                )
                .background(bubbleColor)
        ) {
            if (bubbleImageBitmap != null) {
                Image(
                    bitmap = bubbleImageBitmap,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Column {
                if (hasPhotos) {
                    AlbumPhotoGrid(
                        bitmaps = photoBitmaps,
                        messages = photoMessages,
                        bubbleColor = bubbleColor,
                        textColor = textColor,
                        hasCaption = hasCaption || hasFiles,
                        isOutgoing = isOutgoing,
                        onPhotoClick = onPhotoClick,
                    )
                }

                if (hasFiles) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 8.sdp,
                            vertical = if (hasPhotos) 6.sdp else 8.sdp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.sdp)
                    ) {
                        fileMessages.forEach { fileMsg ->
                            AlbumFileRow(
                                message = fileMsg,
                                textColor = textColor,
                                isDownloading = fileMsg.mediaId?.let { it in downloadingFiles } == true,
                                onTap = { onFileTap(fileMsg) }
                            )
                        }
                    }
                }

                if (hasCaption) {
                    val annotated = buildAnnotatedString {
                        if (searchQuery.isBlank()) {
                            append(caption!!)
                        } else {
                            val lower = caption!!.lowercase()
                            val lowerQ = searchQuery.lowercase()
                            var pos = 0
                            while (pos < caption.length) {
                                val idx = lower.indexOf(lowerQ, pos)
                                if (idx == -1) { append(caption.substring(pos)); break }
                                append(caption.substring(pos, idx))
                                withStyle(SpanStyle(background = Color(0xFFFFD60A))) {
                                    append(caption.substring(idx, idx + searchQuery.length))
                                }
                                pos = idx + searchQuery.length
                            }
                        }
                    }
                    Text(
                        text = annotated,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 12.sdp, vertical = 8.sdp)
                    )
                }

                if (isOutgoing) {
                    val anyNotSent = albumMessages.any { it.status != MessageStatus.SENT && it.status != MessageStatus.READ }
                    if (anyNotSent) {
                        val anySending = albumMessages.any { it.status == MessageStatus.SENDING }
                        Text(
                            text = if (anySending) "⏳" else "❌",
                            fontSize = 11.ssp,
                            color = textColor.copy(alpha = 0.6f),
                            modifier = Modifier
                                .padding(end = 8.sdp, bottom = 4.sdp)
                                .align(Alignment.End)
                        )
                    }
                }
            }

            val albumHighlightAlpha by animateFloatAsState(
                targetValue = if (isHighlighted) 0.35f else 0f,
                animationSpec = tween(durationMillis = 600),
                label = "albumHighlight",
            )
            if (albumHighlightAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = albumHighlightAlpha))
                )
            }
        }

        if (!isOutgoing && timeText.isNotEmpty()) {
            Text(
                timeText, color = timeColor, fontSize = 11.ssp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = 4.sdp, bottom = 4.sdp)
            )
        }
    }
}

@Composable
private fun AlbumFileRow(
    message: Message,
    textColor: Color,
    isDownloading: Boolean,
    onTap: () -> Unit
) {
    val s = LocalStrings.current
    val isFailed = message.status == MessageStatus.FAILED
    val hasLocal = !message.localFilePath.isNullOrBlank()
    val sizeText = message.fileSize?.takeIf { it > 0 }?.let { formatSizeBytes(it) } ?: ""
    val mime = message.fileMime ?: ""
    val fileEmoji = when {
        mime.startsWith("image/") -> "\uD83D\uDDBC"
        mime.startsWith("video/") -> "\uD83C\uDFAC"
        mime.startsWith("audio/") -> "\uD83C\uDFB5"
        mime == "application/pdf" -> "\uD83D\uDCD5"
        mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar") -> "\uD83D\uDDC4"
        mime.contains("word") || mime.contains("document") -> "\uD83D\uDCC4"
        mime.contains("sheet") || mime.contains("excel") -> "\uD83D\uDCCA"
        mime.contains("presentation") || mime.contains("powerpoint") -> "\uD83D\uDCCA"
        mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") -> "\uD83D\uDCDD"
        else -> "\uD83D\uDCCE"
    }
    val errorColor = MaterialTheme.colorScheme.error
    val iconBgColor = if (isFailed) errorColor.copy(alpha = 0.18f) else textColor.copy(alpha = 0.15f)
    val nameColor = if (isFailed) errorColor else textColor
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.sdp))
            .clickable(enabled = !isDownloading && !isFailed) { onTap() }
            .padding(horizontal = 4.sdp, vertical = 4.sdp)
    ) {
        Box(
            modifier = Modifier
                .size(40.sdp)
                .clip(CircleShape)
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            when {
                isFailed -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = errorColor
                )
                isDownloading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.sdp),
                    color = textColor,
                    strokeWidth = 2.sdp
                )
                !hasLocal && (message.status == MessageStatus.SENT || message.status == MessageStatus.READ) -> Icon(
                    Icons.Default.Download,
                    contentDescription = s.downloadFile,
                    tint = textColor
                )
                else -> Text(fileEmoji, fontSize = 20.ssp)
            }
        }
        Spacer(Modifier.width(10.sdp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.fileName ?: s.file,
                color = nameColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (sizeText.isNotEmpty()) {
                Text(
                    text = sizeText,
                    color = textColor.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AlbumPhotoGrid(
    bitmaps: List<ImageBitmap?>,
    messages: List<Message>,
    bubbleColor: Color,
    textColor: Color,
    hasCaption: Boolean,
    isOutgoing: Boolean,
    onPhotoClick: (Int) -> Unit = {},
) {
    val spacing = 2.sdp
    val count = bitmaps.size

    val topStart = 16.sdp
    val topEnd = 16.sdp
    val bottomStart = if (!hasCaption && isOutgoing) 16.sdp else if (!hasCaption) 4.sdp else 0.sdp
    val bottomEnd = if (!hasCaption && isOutgoing) 4.sdp else if (!hasCaption) 16.sdp else 0.sdp

    when (count) {
        0 -> {}
        1 -> {
            AlbumPhotoCell(
                bitmap = bitmaps[0],
                message = messages[0],
                bubbleColor = bubbleColor,
                textColor = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.sdp)
                    .clip(
                        RoundedCornerShape(
                            topStart = topStart, topEnd = topEnd,
                            bottomStart = bottomStart, bottomEnd = bottomEnd
                        )
                    ),
                onClick = { onPhotoClick(messages[0].id) },
            )
        }
        2 -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                AlbumPhotoCell(
                    bitmap = bitmaps[0], message = messages[0],
                    bubbleColor = bubbleColor, textColor = textColor,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(0.8f)
                        .clip(RoundedCornerShape(topStart = topStart, bottomStart = bottomStart)),
                    onClick = { onPhotoClick(messages[0].id) },
                )
                AlbumPhotoCell(
                    bitmap = bitmaps[1], message = messages[1],
                    bubbleColor = bubbleColor, textColor = textColor,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(0.8f)
                        .clip(RoundedCornerShape(topEnd = topEnd, bottomEnd = bottomEnd)),
                    onClick = { onPhotoClick(messages[1].id) },
                )
            }
        }
        3 -> {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    AlbumPhotoCell(
                        bitmap = bitmaps[0], message = messages[0],
                        bubbleColor = bubbleColor, textColor = textColor,
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(topStart = topStart)),
                        onClick = { onPhotoClick(messages[0].id) },
                    )
                    AlbumPhotoCell(
                        bitmap = bitmaps[1], message = messages[1],
                        bubbleColor = bubbleColor, textColor = textColor,
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(topEnd = topEnd)),
                        onClick = { onPhotoClick(messages[1].id) },
                    )
                }
                AlbumPhotoCell(
                    bitmap = bitmaps[2], message = messages[2],
                    bubbleColor = bubbleColor, textColor = textColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                        .clip(RoundedCornerShape(bottomStart = bottomStart, bottomEnd = bottomEnd)),
                    onClick = { onPhotoClick(messages[2].id) },
                )
            }
        }
        4 -> {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    AlbumPhotoCell(
                        bitmap = bitmaps[0], message = messages[0],
                        bubbleColor = bubbleColor, textColor = textColor,
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(topStart = topStart)),
                        onClick = { onPhotoClick(messages[0].id) },
                    )
                    AlbumPhotoCell(
                        bitmap = bitmaps[1], message = messages[1],
                        bubbleColor = bubbleColor, textColor = textColor,
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(topEnd = topEnd)),
                        onClick = { onPhotoClick(messages[1].id) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    AlbumPhotoCell(
                        bitmap = bitmaps[2], message = messages[2],
                        bubbleColor = bubbleColor, textColor = textColor,
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(bottomStart = bottomStart)),
                        onClick = { onPhotoClick(messages[2].id) },
                    )
                    AlbumPhotoCell(
                        bitmap = bitmaps[3], message = messages[3],
                        bubbleColor = bubbleColor, textColor = textColor,
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(bottomEnd = bottomEnd)),
                        onClick = { onPhotoClick(messages[3].id) },
                    )
                }
            }
        }
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                val rows = bitmaps.chunked(2)
                rows.forEachIndexed { rowIdx, rowBitmaps ->
                    val isFirstRow = rowIdx == 0
                    val isLastRow = rowIdx == rows.lastIndex
                    if (rowBitmaps.size == 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            val msgIdx = rowIdx * 2
                            AlbumPhotoCell(
                                bitmap = rowBitmaps[0], message = messages[msgIdx],
                                bubbleColor = bubbleColor, textColor = textColor,
                                modifier = Modifier.weight(1f).aspectRatio(1f).clip(
                                    RoundedCornerShape(
                                        topStart = if (isFirstRow) topStart else 0.sdp,
                                        bottomStart = if (isLastRow) bottomStart else 0.sdp
                                    )
                                ),
                                onClick = { onPhotoClick(messages[msgIdx].id) },
                            )
                            AlbumPhotoCell(
                                bitmap = rowBitmaps[1], message = messages[msgIdx + 1],
                                bubbleColor = bubbleColor, textColor = textColor,
                                modifier = Modifier.weight(1f).aspectRatio(1f).clip(
                                    RoundedCornerShape(
                                        topEnd = if (isFirstRow) topEnd else 0.sdp,
                                        bottomEnd = if (isLastRow) bottomEnd else 0.sdp
                                    )
                                ),
                                onClick = { onPhotoClick(messages[msgIdx + 1].id) },
                            )
                        }
                    } else {
                        val msgIdx = rowIdx * 2
                        AlbumPhotoCell(
                            bitmap = rowBitmaps[0], message = messages[msgIdx],
                            bubbleColor = bubbleColor, textColor = textColor,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f).clip(
                                RoundedCornerShape(
                                    bottomStart = bottomStart,
                                    bottomEnd = bottomEnd
                                )
                            ),
                            onClick = { onPhotoClick(messages[msgIdx].id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumPhotoCell(
    bitmap: ImageBitmap?,
    message: Message,
    bubbleColor: Color,
    textColor: Color,
    modifier: Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier.clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(bubbleColor.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                if (message.status == MessageStatus.SENDING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.sdp),
                        color = textColor,
                        strokeWidth = 2.sdp
                    )
                } else if (message.status != MessageStatus.FAILED) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(28.sdp),
                        tint = textColor.copy(alpha = 0.5f)
                    )
                }
            }
        }
        if (message.status == MessageStatus.FAILED) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Text("❌", fontSize = 20.ssp)
            }
        }
    }
}

@Composable
private fun SwipeToReplyContainer(
    enabled: Boolean = true,
    onReply: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val maxDragPx  = with(density) { 96.sdp.toPx() }
    val triggerPx  = with(density) { 80.sdp.toPx() }
    val slopPx     = with(density) { 24.sdp.toPx() }

    var offsetX by remember { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = tween(durationMillis = 180),
        label = "swipeReplyOffset"
    )
    var triggered by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        val progress = (-animatedOffset / triggerPx).coerceIn(0f, 1f)
        if (progress > 0.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.sdp)
                    .size((28.sdp.value * (0.6f + 0.4f * progress)).sdp)
                    .clip(CircleShape)
                    .background(
                        if (triggered) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = progress)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = if (triggered) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = progress),
                    modifier = Modifier.size(16.sdp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDx = 0f
                        var totalDy = 0f
                        var horizontalLockEngaged = false
                        var verticalLockEngaged = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                if (offsetX <= -triggerPx) {
                                    triggered = true
                                    onReply()
                                }
                                offsetX = 0f
                                triggered = false
                                break
                            }
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            totalDx += dx
                            totalDy += dy

                            if (!horizontalLockEngaged && !verticalLockEngaged) {
                                if (kotlin.math.abs(totalDy) > slopPx &&
                                    kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx) * 1.2f
                                ) {
                                    verticalLockEngaged = true
                                }
                            }

                            if (!horizontalLockEngaged && !verticalLockEngaged) {
                                if (-totalDx > slopPx &&
                                    -totalDx > kotlin.math.abs(totalDy) * 2f
                                ) {
                                    horizontalLockEngaged = true
                                }
                            }
                            if (horizontalLockEngaged) {
                                offsetX = (offsetX + dx).coerceIn(-maxDragPx, 0f)
                                change.consume()
                            }
                        }
                    }
                }
        ) {
            content()
        }
    }
}
