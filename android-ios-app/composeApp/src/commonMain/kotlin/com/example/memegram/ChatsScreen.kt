package com.example.memegram

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    topBarColor: Color,
    onStorageClick: () -> Unit,
    onChatClick: (ChatModel) -> Unit,
    onAppearanceClick: () -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    viewModel: ChatsViewModel,
    onContactsClick: () -> Unit,
    profileViewModel: ProfileViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val chats by viewModel.chats.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val profileUsername by profileViewModel.username.collectAsState()
    val profileAvatar by profileViewModel.avatarBytes.collectAsState()
    val profileCover by profileViewModel.coverBytes.collectAsState()

    var isSearchMode by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchMode) {
        if (isSearchMode) focusRequester.requestFocus()
        else viewModel.setSearchQuery("")
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clickable { scope.launch { drawerState.close(); onProfileClick() } }
                ) {
                    if (profileCover != null) {
                        val coverBitmap = remember(profileCover) { profileCover!!.decodeToImageBitmap() }
                        Image(bitmap = coverBitmap, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(topBarColor)
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (profileAvatar != null) {
                                val avatarBitmap = remember(profileAvatar) { profileAvatar!!.decodeToImageBitmap() }
                                Image(bitmap = avatarBitmap, contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Text(profileUsername.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = profileUsername,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (profileCover != null) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text("Внешний вид") },
                    icon = { Icon(Icons.Default.Palette, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onAppearanceClick() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Уведомления") },
                    icon = { Icon(Icons.Default.Notifications, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onNotificationsClick() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Конфиденциальность") },
                    icon = { Icon(Icons.Default.Lock, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onPrivacyClick() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Данные и память") },
                    icon = { Icon(Icons.Default.Storage, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onStorageClick() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Контакты") },
                    icon = { Icon(Icons.Default.People, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; onContactsClick() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Язык") },
                    icon = { Icon(Icons.Default.Language, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onLanguageClick() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (isSearchMode) {
                            IconButton(onClick = { isSearchMode = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, null, tint = topBarTextColor)
                            }
                        }
                    },
                    title = {
                        AnimatedContent(
                            targetState = isSearchMode,
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { searching ->
                            if (searching) {
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.setSearchQuery(it) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = topBarTextColor, fontSize = 16.sp),
                                    cursorBrush = SolidColor(topBarTextColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester),
                                    decorationBox = { inner ->
                                        if (searchQuery.isEmpty()) {
                                            Text("Поиск...", color = topBarTextColor.copy(alpha = 0.6f), fontSize = 16.sp)
                                        }
                                        inner()
                                    }
                                )
                            } else {
                                Text("Memegram", fontWeight = FontWeight.Bold, color = topBarTextColor)
                            }
                        }
                    },
                    actions = {
                        if (!isSearchMode) {
                            IconButton(onClick = { isSearchMode = true }) {
                                Icon(Icons.Default.Search, null, tint = topBarTextColor)
                            }
                            Box {
                                Surface(
                                    onClick = { showAddMenu = true },
                                    shape = RoundedCornerShape(20.dp),
                                    color = topBarTextColor.copy(alpha = 0.15f),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = "Add",
                                        color = topBarTextColor,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showAddMenu,
                                    onDismissRequest = { showAddMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Создать группу") },
                                        onClick = { showAddMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Group, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Добавить по ключу") },
                                        onClick = { showAddMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Key, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Добавить по QR") },
                                        onClick = { showAddMenu = false },
                                        leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Создать канал") },
                                        onClick = { showAddMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Campaign, null) }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarColor,
                        titleContentColor = topBarTextColor
                    )
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
            ) {
                if (chats.isEmpty() && searchQuery.isNotBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Ничего не найдено", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(chats, key = { it.id }) { chat ->
                            ChatItem(chat = chat, onClick = { onChatClick(chat) })
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatChatTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    return try {
        val instant = Instant.fromEpochMilliseconds(timestampMs)
        val local   = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val now     = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        if (local.date == now.date) {
            "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        } else {
            "${local.day.toString().padStart(2, '0')}.${local.month.number.toString().padStart(2, '0')}"
        }
    } catch (e: Exception) { "" }
}

@Composable
fun ChatItem(chat: ChatModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color(0xFF6075F2)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = chat.name.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = chat.lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Text(
                text = formatChatTime(chat.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = if (chat.unreadCount > 0) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (chat.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = chat.unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}