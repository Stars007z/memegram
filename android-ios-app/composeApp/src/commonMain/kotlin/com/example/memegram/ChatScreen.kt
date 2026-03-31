package com.example.memegram

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.data.gallery.AttachItem
import com.example.memegram.data.gallery.GalleryThumb
import com.example.memegram.data.gallery.buildGallerySections
import com.example.memegram.data.gallery.rememberGalleryLoader
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    topBarColor: Color,
    chatName: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel
) {
    val messages       by viewModel.messages.collectAsState()
    val inputText      by viewModel.inputText.collectAsState()
    val chatBgColor    by viewModel.chatBgColor.collectAsState()
    val myBubbleColor  by viewModel.myBubbleColor.collectAsState()
    val theirBubbleColor by viewModel.theirBubbleColor.collectAsState()
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White

    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    val lastVisibleServerId by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf null
            messages.getOrNull(visible.last().index)?.serverId
        }
    }

    LaunchedEffect(lastVisibleServerId) {
        lastVisibleServerId
            ?.takeIf { it.isNotBlank() }
            ?.let { viewModel.markMessagesRead(it) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    var attachments by remember { mutableStateOf<List<AttachItem>>(emptyList()) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var pendingGallery by remember { mutableStateOf<Set<GalleryThumb>>(emptySet()) }
    val galleryLoader = rememberGalleryLoader()
    var galleryThumbs by remember { mutableStateOf<List<GalleryThumb>>(emptyList()) }
    var galleryLoading by remember { mutableStateOf(false) }
    val gridState      = rememberLazyGridState()
    val gallerySections = remember(galleryThumbs) { buildGallerySections(galleryThumbs) }

    LaunchedEffect(showAttachSheet, galleryLoader.isPermissionGranted) {
        if (showAttachSheet && galleryLoader.isPermissionGranted && galleryThumbs.isEmpty() && !galleryLoading) {
            galleryLoading = true
            galleryThumbs  = galleryLoader.loadRecent(48)
            galleryLoading = false
        }
    }

    val imagePicker = rememberFilePickerLauncher(
        type = PickerType.Image,
        mode = PickerMode.Multiple()
    ) { files ->
        files?.forEach { attachments = attachments + AttachItem.FromPicker(it) }
    }

    val filePicker = rememberFilePickerLauncher(
        type = PickerType.File(),
        mode = PickerMode.Multiple()
    ) { files ->
        files?.forEach { attachments = attachments + AttachItem.FromPicker(it) }
    }

    var isSearchMode     by remember { mutableStateOf(false) }
    var searchQuery      by remember { mutableStateOf("") }
    var currentMatchIdx  by remember { mutableIntStateOf(0) }
    val searchFocus      = remember { FocusRequester() }

    val searchMatches = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else messages.mapIndexedNotNull { i, m ->
            if (m.text.contains(searchQuery, ignoreCase = true)) i else null
        }
    }
    val currentMatchMsgId = remember(searchMatches, currentMatchIdx) {
        if (searchMatches.isNotEmpty()) messages[searchMatches[currentMatchIdx]].id else -1
    }

    LaunchedEffect(searchQuery)   { currentMatchIdx = if (searchMatches.isNotEmpty()) searchMatches.size - 1 else 0 }
    LaunchedEffect(isSearchMode)  { if (isSearchMode) searchFocus.requestFocus() else searchQuery = "" }
    LaunchedEffect(currentMatchIdx, searchMatches.size) {
        if (searchMatches.isNotEmpty()) listState.animateScrollToItem(searchMatches[currentMatchIdx])
    }

    var showMenu        by remember { mutableStateOf(false) }
    var showMuteDialog  by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false; pendingGallery = emptySet() },
            sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment         = Alignment.CenterVertically,
                        horizontalArrangement     = Arrangement.SpaceBetween
                    ) {
                        Text("Галерея", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick        = { filePicker.launch(); showAttachSheet = false },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Файл", fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick        = { imagePicker.launch(); showAttachSheet = false },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Все", fontSize = 13.sp)
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
                                    Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Нет доступа к галерее", color = Color.Gray)
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { imagePicker.launch(); showAttachSheet = false }) {
                                        Text("Открыть галерею")
                                    }
                                }
                            }
                            else -> {
                                LazyVerticalGrid(
                                    state          = gridState,
                                    columns        = GridCells.Fixed(3),
                                    modifier       = Modifier
                                        .fillMaxSize()
                                        .padding(end = 36.dp),
                                    contentPadding = PaddingValues(
                                        start  = 2.dp,
                                        top    = 2.dp,
                                        end    = 2.dp,
                                        bottom = if (pendingGallery.isNotEmpty()) 70.dp else 2.dp
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalArrangement   = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(galleryThumbs, key = { it.id }) { thumb ->
                                        val isSelected = thumb in pendingGallery
                                        val selIdx     = if (isSelected) pendingGallery.toList().indexOf(thumb) + 1 else 0
                                        GalleryGridItem(
                                            thumb      = thumb,
                                            isSelected = isSelected,
                                            selNumber  = selIdx,
                                            onClick    = {
                                                pendingGallery =
                                                    if (isSelected) pendingGallery - thumb
                                                    else pendingGallery + thumb
                                            }
                                        )
                                    }
                                }

                                DateScrubber(
                                    sections   = gallerySections,
                                    totalItems = galleryThumbs.size,
                                    gridState  = gridState,
                                    columns    = 3,
                                    modifier   = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                )
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
                            .padding(horizontal = 16.dp, vertical = 10.dp)
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
                            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Прикрепить ${pendingGallery.size} ${if (pendingGallery.size == 1) "фото" else "фото"}")
                        }
                    }
                }
            }
        }
    }

    if (showMuteDialog) {
        AlertDialog(
            onDismissRequest = { showMuteDialog = false },
            title = { Text("Отключить уведомления") },
            text = {
                Column {
                    listOf("1 час", "8 часов", "24 часа", "Навсегда").forEach { opt ->
                        TextButton(
                            onClick = { showMuteDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(opt, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMuteDialog = false }) { Text("Отмена") } }
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить чат?") },
            text  = { Text("Чат будет удалён для всех участников.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить для всех") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } }
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить историю") },
            text  = { Text("Выберите, для кого очистить историю сообщений.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearMessages(); showClearDialog = false }) {
                    Text("Только у меня")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showClearDialog = false }) { Text("Отмена") }
                    TextButton(
                        onClick = { viewModel.clearMessages(); showClearDialog = false },
                        colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("У всех") }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (isSearchMode) isSearchMode = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(topBarTextColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(chatName.take(1).uppercase(), color = topBarTextColor, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
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
                                DropdownMenuItem(text = { Text("Поиск") },          leadingIcon = { Icon(Icons.Default.Search, null) },           onClick = { showMenu = false; isSearchMode = true })
                                DropdownMenuItem(text = { Text("Звонок") },         leadingIcon = { Icon(Icons.Default.Call, null) },              onClick = { showMenu = false })
                                DropdownMenuItem(text = { Text("Уведомления") },    leadingIcon = { Icon(Icons.Default.NotificationsOff, null) },  onClick = { showMenu = false; showMuteDialog = true })
                                DropdownMenuItem(text = { Text("Сменить обои") },   leadingIcon = { Icon(Icons.Default.Wallpaper, null) },         onClick = { showMenu = false })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Очистить историю") }, leadingIcon = { Icon(Icons.Default.CleaningServices, null) }, onClick = { showMenu = false; showClearDialog = true })
                                DropdownMenuItem(
                                    text = { Text("Удалить чат", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showMenu = false; showDeleteDialog = true }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarColor)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Column {
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(attachments) { item ->
                                AttachmentThumbnail(
                                    item     = item,
                                    onRemove = { attachments = attachments - item }
                                )
                            }
                        }
                        HorizontalDivider()
                    }

                    if (isSearchMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                .navigationBarsPadding(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value    = searchQuery,
                                onValueChange = { searchQuery = it; currentMatchIdx = 0 },
                                singleLine = true,
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f).focusRequester(searchFocus),
                                decorationBox = { inner ->
                                    Box(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        if (searchQuery.isEmpty()) Text("Поиск в чате...", color = Color.Gray, fontSize = 16.sp)
                                        inner()
                                    }
                                }
                            )
                            Text(
                                if (searchMatches.isNotEmpty()) "${searchMatches.size - currentMatchIdx}/${searchMatches.size}" else if (searchQuery.isNotBlank()) "0/0" else "",
                                modifier = Modifier.padding(horizontal = 8.dp),
                                color = Color.Gray, fontSize = 13.sp
                            )
                            IconButton(
                                onClick  = { if (searchMatches.isNotEmpty()) currentMatchIdx = (currentMatchIdx - 1 + searchMatches.size) % searchMatches.size },
                                enabled  = searchMatches.size > 1
                            ) { Icon(Icons.Default.KeyboardArrowUp, null) }
                            IconButton(
                                onClick  = { if (searchMatches.isNotEmpty()) currentMatchIdx = (currentMatchIdx + 1) % searchMatches.size },
                                enabled  = searchMatches.size > 1
                            ) { Icon(Icons.Default.KeyboardArrowDown, null) }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                .navigationBarsPadding(),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            IconButton(onClick = { }) {
                                Icon(Icons.Default.SentimentSatisfied, null, tint = Color.Gray, modifier = Modifier.size(26.dp))
                            }
                            OutlinedTextField(
                                value         = inputText,
                                onValueChange = { viewModel.updateInput(it) },
                                modifier      = Modifier.weight(1f),
                                placeholder   = { Text("Сообщение...") },
                                shape         = RoundedCornerShape(24.dp),
                                maxLines      = 5
                            )
                            IconButton(onClick = {
                                galleryLoader.requestPermission()
                                showAttachSheet = true
                            }) {
                                Icon(Icons.Default.AttachFile, null, tint = Color.Gray, modifier = Modifier.size(26.dp).rotate(45f))
                            }
                            val isEmpty = inputText.trim().isEmpty() && attachments.isEmpty()
                            IconButton(
                                onClick  = { if (!isEmpty) { viewModel.sendMessage(); attachments = emptyList() } },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (isEmpty) Color.Transparent else MaterialTheme.colorScheme.primary)
                            ) {
                                if (isEmpty) Icon(Icons.Default.Mic, null, tint = Color.Gray, modifier = Modifier.size(26.dp))
                                else Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(chatBgColor)
                .padding(paddingValues)
        ) {
            LazyColumn(
                state          = listState,
                modifier       = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
            ) {
                items(messages, key = { it.id }) { message ->
                    if (message.text.isBlank()) return@items
                    MessageBubble(
                        message          = message,
                        myBubbleColor    = myBubbleColor,
                        theirBubbleColor = theirBubbleColor,
                        searchQuery      = searchQuery,
                        isCurrentMatch   = message.id == currentMatchMsgId
                    )
                }
            }
        }
    }
}

@Composable
fun GalleryGridItem(
    thumb: GalleryThumb,
    isSelected: Boolean,
    selNumber: Int,
    onClick: () -> Unit
) {
    val bitmap = remember(thumb.id) {
        runCatching { thumb.bytes.decodeToImageBitmap() }.getOrNull()
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
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
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else Color.Black.copy(alpha = 0.35f)
                )
                .border(1.5.dp, Color.White, CircleShape)
                .align(Alignment.TopEnd),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text(selNumber.toString(), color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun AnimatedConfirmBar(count: Int, onConfirm: () -> Unit) {
    if (count > 0) {
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Button(
                onClick  = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Прикрепить $count ${if (count == 1) "фото" else "фото"}")
            }
        }
    }
}

@Composable
fun AttachmentThumbnail(item: AttachItem, onRemove: () -> Unit) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(item) {
        try {
            val bytes: ByteArray = when (item) {
                is AttachItem.FromPicker  -> item.file.readBytes()
                is AttachItem.FromGallery -> item.thumb.bytes
            }
            bitmap = bytes.decodeToImageBitmap()
        } catch (_: Exception) { }
    }

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
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
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(item.name, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .align(Alignment.TopEnd)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return try {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val local   = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    } catch (e: Exception) { "" }
}

@Composable
fun MessageBubble(
    message: Message,
    myBubbleColor: Color,
    theirBubbleColor: Color,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false
) {
    val isOut       = message.isOutgoing
    val bubbleColor = if (isCurrentMatch) MaterialTheme.colorScheme.tertiary
    else if (isOut) myBubbleColor else theirBubbleColor
    val textColor   = if (bubbleColor.luminance() > 0.5f) Color.Black else Color.White
    val timeText    = remember(message.timestamp) { formatMessageTime(message.timestamp) }
    val timeColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment     = Alignment.Bottom
    ) {
        if (isOut && timeText.isNotEmpty()) {
            Text(
                text     = timeText,
                color    = timeColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart    = 16.dp,
                        topEnd      = 16.dp,
                        bottomStart = if (isOut) 16.dp else 4.dp,
                        bottomEnd   = if (isOut) 4.dp else 16.dp
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val annotated = buildAnnotatedString {
                if (searchQuery.isBlank()) {
                    append(message.text)
                } else {
                    val lower  = message.text.lowercase()
                    val lowerQ = searchQuery.lowercase()
                    var pos    = 0
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
            Text(
                text  = annotated,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        if (!isOut && timeText.isNotEmpty()) {
            Text(
                text     = timeText,
                color    = timeColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }
    }
}