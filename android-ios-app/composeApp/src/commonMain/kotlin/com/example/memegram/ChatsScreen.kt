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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.example.memegram.audio.GlobalAudioPlayer
import com.example.memegram.audio.VoicePlaybackBar
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.CreateInviteRequest
import com.example.memegram.data.network.ApiService
import com.example.memegram.localization.LocalStrings
import kotlin.time.Clock
import kotlin.time.Instant
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    topBarColor: Color,
    onStorageClick: () -> Unit,
    onChatClick: (ChatModel) -> Unit,
    onNavigateToChat: (convId: String, chatName: String?, avatarMediaId: String?) -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    onAppearanceClick: () -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    viewModel: ChatsViewModel,
    onContactsClick: () -> Unit,
    onLinkedDevicesClick: () -> Unit,
    profileViewModel: ProfileViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val s = LocalStrings.current
    val chats by viewModel.chats.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val blockedConvIds by viewModel.blockedConversationIds.collectAsState()

    val globalAudioPlayer = koinInject<GlobalAudioPlayer>()
    val audioPlaybackState by globalAudioPlayer.state.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val profileUsername by profileViewModel.username.collectAsState()
    val profileAvatar by profileViewModel.avatarBytes.collectAsState()
    val profileCover by profileViewModel.coverBytes.collectAsState()

    var isSearchMode by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val contactsVm: ContactsViewModel = koinViewModel()
    val createdChatId by contactsVm.chatCreated.collectAsState()
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var newKeyInput by remember { mutableStateOf("") }

    val sessionManager = koinInject<SessionManager>()
    val apiService = koinInject<ApiService>()
    val isAdmin = remember { sessionManager.getDeviceType() == "admin" }
    var showInviteDialog by remember { mutableStateOf(false) }
    var inviteDays by remember { mutableStateOf("7") }
    var isCreatingInvite by remember { mutableStateOf(false) }
    var createdInviteCode by remember { mutableStateOf<String?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        profileViewModel.loadProfile()
    }

    LaunchedEffect(isSearchMode) {
        if (isSearchMode) focusRequester.requestFocus()
        else viewModel.setSearchQuery("")
    }
    LaunchedEffect(createdChatId) {
        createdChatId?.let { id ->
            val chatName = contactsVm.getPendingChatName()
            val avatarMediaId = contactsVm.getPendingChatAvatarMediaId()
            contactsVm.clearChatCreated()
            contactsVm.clearPendingChatName()
            contactsVm.clearPendingChatAvatarMediaId()
            onNavigateToChat(id, chatName, avatarMediaId)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.5f)
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
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.sdp, bottom = 8.sdp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.sdp)
                                .clip(CircleShape)
                                .background(topBarColor)
                                .border(2.sdp, Color.White, CircleShape),
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
                        Spacer(Modifier.height(4.sdp))
                        Text(
                            text = profileUsername,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (profileCover != null) Color.White else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider()
                Spacer(Modifier.height(8.sdp))

                NavigationDrawerItem(
                    label = { Text(s.settingsAppearance) },
                    icon = { Icon(Icons.Default.Palette, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onAppearanceClick() } },
                    modifier = Modifier.padding(horizontal = 12.sdp)
                )
                NavigationDrawerItem(
                    label = { Text(s.settingsNotifications) },
                    icon = { Icon(Icons.Default.Notifications, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onNotificationsClick() } },
                    modifier = Modifier.padding(horizontal = 12.sdp)
                )
                NavigationDrawerItem(
                    label = { Text(s.settingsPrivacy) },
                    icon = { Icon(Icons.Default.Lock, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onPrivacyClick() } },
                    modifier = Modifier.padding(horizontal = 12.sdp)
                )
                NavigationDrawerItem(
                    label = { Text(s.settingsDataAndStorage) },
                    icon = { Icon(Icons.Default.Storage, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onStorageClick() } },
                    modifier = Modifier.padding(horizontal = 12.sdp)
                )
                NavigationDrawerItem(
                    label = { Text(s.settingsContacts) },
                    icon = { Icon(Icons.Default.People, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; onContactsClick() },
                    modifier = Modifier.padding(horizontal = 12.sdp)
                )
                NavigationDrawerItem(
                    label   = { Text(s.settingsLinkedDevices) },
                    icon    = { Icon(Icons.Default.Devices, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; onLinkedDevicesClick() },
                    modifier = Modifier.padding(horizontal = 12.sdp)
                )
                NavigationDrawerItem(
                    label = { Text(s.settingsLanguage) },
                    icon = { Icon(Icons.Default.Language, null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onLanguageClick() } },
                    modifier = Modifier.padding(horizontal = 12.sdp)
                )
                if (isAdmin) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.sdp))
                    NavigationDrawerItem(
                        label = { Text(s.createInvite) },
                        icon = { Icon(Icons.Default.CardGiftcard, null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; showInviteDialog = true },
                        modifier = Modifier.padding(horizontal = 12.sdp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                ImageTopAppBarBox(topBarColor) { bgColor ->
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
                                    textStyle = TextStyle(color = topBarTextColor, fontSize = 16.ssp),
                                    cursorBrush = SolidColor(topBarTextColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester),
                                    decorationBox = { inner ->
                                        if (searchQuery.isEmpty()) {
                                            Text(s.searchPlaceholder, color = topBarTextColor.copy(alpha = 0.6f), fontSize = 16.ssp)
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
                                    shape = RoundedCornerShape(20.sdp),
                                    color = topBarTextColor.copy(alpha = 0.15f),
                                    modifier = Modifier.padding(end = 8.sdp)
                                ) {
                                    Text(
                                        text = "Add",
                                        color = topBarTextColor,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.ssp,
                                        modifier = Modifier.padding(horizontal = 14.sdp, vertical = 6.sdp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showAddMenu,
                                    onDismissRequest = { showAddMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(s.createGroup) },
                                        onClick = {
                                            showAddMenu = false
                                            onNavigateToCreateGroup()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Group, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(s.addByKey) },
                                        onClick = {
                                            showAddMenu = false
                                            showAddKeyDialog = true
                                        },
                                        leadingIcon = { Icon(Icons.Default.Key, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(s.addByQr) },
                                        onClick = { showAddMenu = false },
                                        leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(s.createChannel) },
                                        onClick = { showAddMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Campaign, null) }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bgColor,
                        titleContentColor = topBarTextColor
                    )
                )
                }
                if (showAddKeyDialog) {
                    AlertDialog(
                        onDismissRequest = { showAddKeyDialog = false },
                        title = { Text(s.newChat) },
                        text = {
                            Column {
                                Text(s.enterPublicKeyToChat, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.sdp))
                                OutlinedTextField(
                                    value = newKeyInput,
                                    onValueChange = { newKeyInput = it },
                                    label = { Text(s.publicKey) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showAddKeyDialog = false
                                    if (newKeyInput.isNotBlank()) {
                                        contactsVm.addAndStartChat(newKeyInput.trim())
                                    }
                                    newKeyInput = ""
                                }
                            ) {
                                Text(s.start)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddKeyDialog = false }) {
                                Text(s.cancel)
                            }
                        }
                    )
                }
                if (showInviteDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            if (!isCreatingInvite) {
                                showInviteDialog = false
                                createdInviteCode = null
                                inviteError = null
                            }
                        },
                        title = { Text(s.createInviteTitle) },
                        text = {
                            Column {
                                if (createdInviteCode != null) {
                                    Text(s.inviteCreated, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.sdp))
                                    SelectionContainer {
                                        Text(
                                            text = createdInviteCode!!,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = inviteDays,
                                        onValueChange = { inviteDays = it.filter { c -> c.isDigit() } },
                                        label = { Text(s.createInviteExpiresDays) },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isCreatingInvite,
                                        singleLine = true
                                    )
                                    inviteError?.let { err ->
                                        Spacer(Modifier.height(8.sdp))
                                        Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.ssp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            if (createdInviteCode != null) {
                                TextButton(onClick = {
                                    showInviteDialog = false
                                    createdInviteCode = null
                                }) { Text(s.close) }
                            } else {
                                TextButton(
                                    onClick = {
                                        val days = inviteDays.toIntOrNull()
                                        if (days == null || days < 1) {
                                            inviteError = "Invalid days"
                                            return@TextButton
                                        }
                                        isCreatingInvite = true
                                        inviteError = null
                                        scope.launch {
                                            try {
                                                val resp = apiService.createInvite(CreateInviteRequest(days))
                                                createdInviteCode = resp.code
                                            } catch (e: Exception) {
                                                inviteError = e.message
                                            } finally {
                                                isCreatingInvite = false
                                            }
                                        }
                                    },
                                    enabled = !isCreatingInvite
                                ) {
                                    if (isCreatingInvite) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.sdp), strokeWidth = 2.sdp)
                                    } else {
                                        Text(s.create)
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            if (createdInviteCode == null) {
                                TextButton(
                                    onClick = { showInviteDialog = false; inviteError = null },
                                    enabled = !isCreatingInvite
                                ) { Text(s.cancel) }
                            }
                        }
                    )
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
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (chats.isEmpty() && searchQuery.isNotBlank()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.nothingFound, color = Color.Gray)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(chats, key = { it.id }) { chat ->
                                ChatItem(
                                    chat = chat,
                                    onClick = { onChatClick(chat) },
                                    isBlocked = chat.conversationId in blockedConvIds
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 76.sdp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
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
fun ChatItem(chat: ChatModel, onClick: () -> Unit, isBlocked: Boolean = false) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.sdp, vertical = 12.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            mediaId = chat.avatarMediaId,
            size = 50.sdp,
            fallbackLetter = chat.name.take(1).uppercase(),
            backgroundColor = Color(0xFF6075F2)
        )

        Spacer(modifier = Modifier.width(16.sdp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isBlocked) {
                    Spacer(Modifier.width(6.sdp))
                    Icon(
                        Icons.Default.Block, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.sdp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.sdp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!chat.isLastMessageMine && !chat.lastSenderName.isNullOrBlank()) {
                    AvatarImage(
                        mediaId = chat.lastSenderAvatarMediaId,
                        size = 18.sdp,
                        fallbackLetter = chat.lastSenderName.take(1).uppercase(),
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.sdp))
                } else if (chat.isLastMessageMine) {
                    Text(
                        text = s.youPrefix,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = chat.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.sdp)
        ) {
            Text(
                text = formatChatTime(chat.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = if (chat.unreadCount > 0) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(modifier = Modifier.height(4.sdp))
            if (chat.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(24.sdp)
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