package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.data.models.ContactEntry
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    onChatClick: (ChatModel) -> Unit,
    viewModel: ContactsViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val s = LocalStrings.current
    val contacts by viewModel.contacts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isAdding by viewModel.isAdding.collectAsState()
    val error by viewModel.error.collectAsState()
    val addSuccess by viewModel.addSuccess.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var publicKeyInput by remember { mutableStateOf("") }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
    var pendingBlockId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(addSuccess) {
        if (addSuccess) {
            showAddDialog = false
            publicKeyInput = ""
            viewModel.resetAddSuccess()
        }
    }

    val chatCreated by viewModel.chatCreated.collectAsState()

    LaunchedEffect(chatCreated) {
        val conversationId = chatCreated
        if (conversationId != null) {
            val chatName = viewModel.getPendingChatName() ?: s.chatFallback
            viewModel.clearChatCreated()
            viewModel.clearPendingChatName()
            onChatClick(
                ChatModel(
                    id             = conversationId.hashCode(),
                    conversationId = conversationId,
                    name           = chatName,
                    lastMessage    = "",
                    timestamp      = 0L
                )
            )
        }
    }
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(s.error) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!isAdding) showAddDialog = false },
            title = { Text(s.newContact) },
            text = {
                Column {
                    Text(
                        s.enterPublicKey,
                        fontSize = 13.ssp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.sdp)
                    )
                    OutlinedTextField(
                        value = publicKeyInput,
                        onValueChange = { publicKeyInput = it },
                        label = { Text(s.publicKey) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.sdp),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isAdding,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = topBarColor,
                            focusedLabelColor = topBarColor
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.addContact(publicKeyInput.trim()) },
                    enabled = publicKeyInput.isNotBlank() && !isAdding,
                    colors = ButtonDefaults.buttonColors(containerColor = topBarColor)
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.sdp),
                            strokeWidth = 2.sdp,
                            color = Color.White
                        )
                    } else {
                        Text(s.add)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddDialog = false; publicKeyInput = "" },
                    enabled = !isAdding
                ) { Text(s.cancel) }
            }
        )
    }

    pendingRemoveId?.let { userId ->
        val name = contacts.find { it.contactUserId == userId }
            ?.profile?.username ?: (userId.take(8) + "...")
        AlertDialog(
            onDismissRequest = { pendingRemoveId = null },
            title = { Text(s.deleteContactTitle) },
            text = { Text(s.deleteContactMessage(name)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.removeContact(userId); pendingRemoveId = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(s.delete) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveId = null }) { Text(s.cancel) }
            }
        )
    }

    pendingBlockId?.let { userId ->
        val name = contacts.find { it.contactUserId == userId }
            ?.profile?.username ?: (userId.take(8) + "...")
        AlertDialog(
            onDismissRequest = { pendingBlockId = null },
            title = { Text(s.blockTitle) },
            text = { Text(s.blockMessage(name)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.blockUser(userId); pendingBlockId = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(s.blockAction) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlockId = null }) { Text(s.cancel) }
            }
        )
    }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                title = { Text(s.contactsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.PersonAdd, null, tint = topBarTextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bgColor,
                    titleContentColor = topBarTextColor,
                    actionIconContentColor = topBarTextColor,
                    navigationIconContentColor = topBarTextColor
                )
            )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                contacts.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = null,
                            modifier = Modifier.size(64.sdp),
                            tint = Color.LightGray
                        )
                        Spacer(Modifier.height(12.sdp))
                        Text(
                            s.noContacts,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(4.sdp))
                        Text(
                            s.addContactHint,
                            fontSize = 13.ssp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.sdp)
                        )
                    }
                }

                else -> {
                    val favorites = contacts.filter { it.isFavorite }
                    val others = contacts.filter { !it.isFavorite }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (favorites.isNotEmpty()) {
                            item { SectionHeader(s.favorites, topBarColor) }
                            items(favorites, key = { it.contactUserId }) { entry ->
                                ContactItem(
                                    entry = entry,
                                    accentColor = topBarColor,
                                    onChatClick = {
                                        viewModel.startDirectChatWith(entry)
                                    },
                                    onFavoriteToggle = {
                                        viewModel.toggleFavorite(entry.contactUserId)
                                    },
                                    onRemove = { pendingRemoveId = entry.contactUserId },
                                    onBlock = { pendingBlockId = entry.contactUserId }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.sdp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }

                        if (others.isNotEmpty()) {
                            item { SectionHeader(s.allContacts, topBarColor) }
                            items(others, key = { it.contactUserId }) { entry ->
                                ContactItem(
                                    entry = entry,
                                    accentColor = topBarColor,
                                    onChatClick = {
                                        viewModel.startDirectChatWith(entry)
                                    },
                                    onFavoriteToggle = {
                                        viewModel.toggleFavorite(entry.contactUserId)
                                    },
                                    onRemove = { pendingRemoveId = entry.contactUserId },
                                    onBlock = { pendingBlockId = entry.contactUserId }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.sdp),
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

@Composable
private fun SectionHeader(title: String, accentColor: Color) {
    Text(
        text = title,
        fontSize = 12.ssp,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        modifier = Modifier.padding(horizontal = 16.sdp, vertical = 8.sdp)
    )
}

@Composable
private fun ContactItem(
    entry: ContactEntry,
    accentColor: Color,
    onChatClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit
) {
    val displayName = entry.profile?.username
        ?.takeIf { it.isNotBlank() }
        ?: "@${entry.contactUserId.take(8)}"
    val s = LocalStrings.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.sdp, vertical = 10.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            mediaId = entry.profile?.avatarMediaId,
            size = 46.sdp,
            fallbackLetter = displayName.take(1).uppercase(),
            backgroundColor = if (entry.isFavorite) accentColor.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant,
            textColor = if (entry.isFavorite) accentColor else Color.Gray
        )

        Spacer(Modifier.width(14.sdp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.ssp
            )
            entry.profile?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(
                    text = bio,
                    fontSize = 12.ssp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }
        }

        IconButton(onClick = onChatClick) {
            Icon(Icons.Default.ChatBubbleOutline, null, tint = accentColor)
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, null, tint = Color.Gray)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(if (entry.isFavorite) s.removeFromFavorites else s.addToFavorites)
                    },
                    leadingIcon = {
                        Icon(
                            if (entry.isFavorite) Icons.Default.StarBorder else Icons.Default.Star,
                            null,
                            tint = accentColor
                        )
                    },
                    onClick = { onFavoriteToggle(); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text(s.delete, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PersonRemove,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { onRemove(); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text(s.blockAction, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Block,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { onBlock(); showMenu = false }
                )
            }
        }
    }
}