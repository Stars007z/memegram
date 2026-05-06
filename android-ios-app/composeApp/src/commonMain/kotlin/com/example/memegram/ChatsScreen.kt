package com.example.memegram

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
import com.example.memegram.utils.resolveTopBarTextColor

object ChatsSwipeBackPreviewCache {
    var chats by mutableStateOf<List<ChatModel>>(emptyList())
    var blockedConversationIds by mutableStateOf<Set<String>>(emptySet())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    topBarColor: Color,
    onStorageClick: () -> Unit,
    onChatClick: (ChatModel) -> Unit,
    onNavigateToChat: (convId: String, chatName: String?, avatarMediaId: String?, messageId: Int?) -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    onAppearanceClick: () -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    viewModel: ChatsViewModel,
    onContactsClick: () -> Unit,
    profileViewModel: ProfileViewModel
) {
    val topBarTextColor = resolveTopBarTextColor(topBarColor)
    val s = LocalStrings.current
    val chats by viewModel.chats.collectAsState()
    val searchMessageResults by viewModel.searchMessageResults.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val blockedConvIds by viewModel.blockedConversationIds.collectAsState()
    val selectedIds by viewModel.selectedChatIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()

    SideEffect {
        ChatsSwipeBackPreviewCache.chats = chats
        ChatsSwipeBackPreviewCache.blockedConversationIds = blockedConvIds
    }

    var pendingMuteIds by remember { mutableStateOf<Set<String>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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

    BlockedByPeerDialog(contactsVm)

    var showAddKeyDialog by remember { mutableStateOf(false) }
    var newKeyInput by remember { mutableStateOf("") }

    val sessionManager = koinInject<SessionManager>()
    val apiService = koinInject<ApiService>()
    val isAdmin = remember { sessionManager.getDeviceType() in setOf("admin", "primary") }
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
            onNavigateToChat(id, chatName, avatarMediaId, null)
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
                    val coverBitmap = remember(profileCover) {
                        profileCover?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
                    }
                    if (coverBitmap != null) {
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
                            val avatarBitmap = remember(profileAvatar) {
                                profileAvatar?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
                            }
                            if (avatarBitmap != null) {
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
                if (isSelectionMode) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, null, tint = topBarTextColor)
                            }
                        },
                        title = {
                            Text(
                                "${selectedIds.size}",
                                color = topBarTextColor,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        actions = {
                            IconButton(onClick = { pendingMuteIds = selectedIds }) {
                                Icon(Icons.Default.NotificationsOff, null, tint = topBarTextColor)
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Default.Delete, null, tint = topBarTextColor)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = bgColor,
                            titleContentColor = topBarTextColor
                        )
                    )
                } else {
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
                    val clipboardManager = LocalClipboardManager.current
                    var inviteCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(createdInviteCode) { inviteCopied = false }
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        SelectionContainer(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = createdInviteCode!!,
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(onClick = {
                                            clipboardManager.setText(AnnotatedString(createdInviteCode!!))
                                            inviteCopied = true
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = s.copyCode
                                            )
                                        }
                                    }
                                    if (inviteCopied) {
                                        Spacer(Modifier.height(4.sdp))
                                        Text(
                                            text = s.copied,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 12.ssp
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

                pendingMuteIds?.let { ids ->
                    AlertDialog(
                        onDismissRequest = { pendingMuteIds = null },
                        title = { Text(s.muteNotifications) },
                        text = {
                            Column {
                                listOf(
                                    s.mute1Hour to 60L * 60_000L,
                                    s.mute8Hours to 8L * 60L * 60_000L,
                                    s.mute24Hours to 24L * 60L * 60_000L,
                                    s.muteForever to Long.MAX_VALUE
                                ).forEach { (label, durMs) ->
                                    Text(
                                        text = label,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.muteChats(ids, durMs)
                                                pendingMuteIds = null
                                                viewModel.clearSelection()
                                            }
                                            .padding(vertical = 12.sdp, horizontal = 8.sdp),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                val anyMuted = ids.any { id ->
                                    chats.any { it.conversationId == id && it.muteUntil > Clock.System.now().toEpochMilliseconds() }
                                }
                                if (anyMuted) {
                                    HorizontalDivider()
                                    Text(
                                        text = s.muteNotifications + " — " + s.cancel,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.muteChats(ids, 0L)
                                                pendingMuteIds = null
                                                viewModel.clearSelection()
                                            }
                                            .padding(vertical = 12.sdp, horizontal = 8.sdp),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { pendingMuteIds = null }) { Text(s.cancel) }
                        }
                    )
                }

                if (showDeleteConfirm) {
                    val anyGroup = chats.any { it.conversationId in selectedIds && it.isGroup }
                    val title = if (anyGroup) s.leaveGroupTitle else s.deleteChatTitle
                    val msg = if (anyGroup) s.leaveGroupMessage else s.deleteChatMessage
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text(title) },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConfirm = false
                                viewModel.deleteSelectedChats()
                            }) { Text(s.delete, color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text(s.cancel) }
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
                    val showSearchResults = isSearchMode && searchQuery.isNotBlank()
                    val totalSearchResults = chats.size + searchMessageResults.size

                    if (showSearchResults && totalSearchResults == 0) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.nothingFound, color = Color.Gray)
                        }
                    } else if (showSearchResults) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item(key = "search-summary") {
                                SearchResultsSummary(
                                    total = totalSearchResults
                                )
                            }

                            if (chats.isNotEmpty()) {
                                item(key = "chat-results-header") {
                                    SearchSectionHeader(s.chatResults, chats.size)
                                }
                                items(chats, key = { "chat:${it.conversationId}" }) { chat ->
                                    ChatItem(
                                        chat = chat,
                                        query = searchQuery,
                                        isSelected = false,
                                        isSelectionMode = false,
                                        onClick = { onChatClick(chat) },
                                        onLongClick = {},
                                        onMute = {},
                                        onDelete = {},
                                        isBlocked = chat.conversationId in blockedConvIds,
                                        modifier = Modifier.animateItem()
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 76.sdp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                            }

                            if (searchMessageResults.isNotEmpty()) {
                                item(key = "message-results-header") {
                                    SearchSectionHeader(s.messageResults, searchMessageResults.size)
                                }
                                items(searchMessageResults, key = { "message:${it.chat.conversationId}:${it.message.serverId.ifBlank { it.message.id.toString() }}" }) { result ->
                                    MessageSearchResultItem(
                                        result = result,
                                        query = searchQuery,
                                        onClick = {
                                            onNavigateToChat(
                                                result.chat.conversationId,
                                                result.chat.name,
                                                result.chat.avatarMediaId,
                                                result.message.id
                                            )
                                        },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                                item(key = "search-bottom-space") {
                                    Spacer(Modifier.height(12.sdp))
                                }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(chats, key = { it.id }) { chat ->
                                ChatItem(
                                    chat = chat,
                                    query = "",
                                    isSelected = chat.conversationId in selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) viewModel.toggleSelection(chat.conversationId)
                                        else onChatClick(chat)
                                    },
                                    onLongClick = {
                                        viewModel.toggleSelection(chat.conversationId)
                                    },
                                    onMute = { pendingMuteIds = setOf(chat.conversationId) },
                                    onDelete = {
                                        viewModel.toggleSelection(chat.conversationId)
                                        showDeleteConfirm = true
                                    },
                                    isBlocked = chat.conversationId in blockedConvIds,
                                    modifier = Modifier.animateItem()
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

private fun formatSearchResultTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    return try {
        val instant = Instant.fromEpochMilliseconds(timestampMs)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        if (local.date == now.date) time
        else "${local.day.toString().padStart(2, '0')}.${local.month.number.toString().padStart(2, '0')} $time"
    } catch (_: Exception) { "" }
}

@Composable
private fun highlightedText(
    text: String,
    query: String,
    highlightColor: Color = Color(0xFFFFD60A),
    highlightTextColor: Color = Color.Black,
): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lower = text.lowercase()
    val lowerQ = query.lowercase()
    var pos = 0
    while (pos < text.length) {
        val idx = lower.indexOf(lowerQ, pos)
        if (idx == -1) {
            append(text.substring(pos))
            break
        }
        append(text.substring(pos, idx))
        withStyle(
            SpanStyle(
                background = highlightColor,
                color = highlightTextColor,
                fontWeight = FontWeight.SemiBold,
            )
        ) {
            append(text.substring(idx, (idx + query.length).coerceAtMost(text.length)))
        }
        pos = idx + query.length
    }
}

private fun searchSnippet(text: String, query: String, radius: Int = 72): String {
    if (query.isBlank() || text.length <= radius * 2) return text
    val idx = text.lowercase().indexOf(query.lowercase())
    if (idx < 0) return text
    val start = (idx - radius).coerceAtLeast(0)
    val end = (idx + query.length + radius).coerceAtMost(text.length)
    return buildString {
        if (start > 0) append("...")
        append(text.substring(start, end).trim())
        if (end < text.length) append("...")
    }
}

@Composable
private fun SearchResultsSummary(
    total: Int,
) {
    val s = LocalStrings.current
    Text(
        text = s.searchResultsCount(total),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.sdp, end = 18.sdp, top = 14.sdp, bottom = 4.sdp)
    )
}

@Composable
private fun SearchSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.sdp, vertical = 8.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.sdp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.sdp, vertical = 2.sdp),
            )
        }
        Spacer(Modifier.width(10.sdp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun MessageSearchResultItem(
    result: ChatSearchResult,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var pressed by remember { mutableStateOf(false) }
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.12f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "searchPressAlpha"
    )
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(140)
            pressed = false
        }
    }

    Surface(
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.sdp, vertical = 3.sdp)
            .clip(RoundedCornerShape(20.sdp))
            .clickable {
                pressed = true
                onClick()
            }
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary.copy(alpha = pressAlpha))
                .padding(horizontal = 8.sdp, vertical = 9.sdp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                AvatarImage(
                    mediaId = result.chat.avatarMediaId,
                    size = 46.sdp,
                    fallbackLetter = result.chat.name.take(1).uppercase(),
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    textColor = MaterialTheme.colorScheme.onPrimary,
                )

                Spacer(Modifier.width(12.sdp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = highlightedText(result.chat.name, query),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val time = formatSearchResultTime(result.message.timestamp)
                        if (time.isNotBlank()) {
                            Spacer(Modifier.width(8.sdp))
                            Text(
                                text = time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }

                    Spacer(Modifier.height(5.sdp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (result.message.isOutgoing) {
                            Text(
                                text = s.you,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else if (!result.senderName.isNullOrBlank()) {
                            AvatarImage(
                                mediaId = result.senderAvatarMediaId,
                                size = 18.sdp,
                                fallbackLetter = result.senderName.take(1).uppercase(),
                                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                textStyle = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.width(6.sdp))
                            Text(
                                text = result.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                    }

                    Spacer(Modifier.height(6.sdp))

                    Text(
                        text = highlightedText(searchSnippet(result.displayText, query), query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(8.sdp))
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .padding(top = 22.sdp)
                        .size(22.sdp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatItem(
    chat: ChatModel,
    query: String = "",
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMute: () -> Unit = {},
    onDelete: () -> Unit = {},
    isBlocked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val nowMs = remember { Clock.System.now().toEpochMilliseconds() }
    val isMuted = chat.muteUntil > nowMs
    val rowBg = if (isSelected)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else if (query.isNotBlank())
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)
    else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (query.isBlank()) 0.sdp else 18.sdp))
            .background(rowBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.sdp, vertical = 12.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            AvatarImage(
                mediaId = chat.avatarMediaId,
                size = 50.sdp,
                fallbackLetter = chat.name.take(1).uppercase(),
                backgroundColor = Color(0xFF6075F2)
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.sdp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.sdp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check, null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.sdp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.sdp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = highlightedText(chat.name, query),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isMuted) {
                    Spacer(Modifier.width(6.sdp))
                    Icon(
                        Icons.Default.NotificationsOff, null,
                        tint = Color.Gray,
                        modifier = Modifier.size(16.sdp)
                    )
                }
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
                    text = highlightedText(chat.lastMessage, query),
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
