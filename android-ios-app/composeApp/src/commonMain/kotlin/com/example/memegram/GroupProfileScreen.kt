package com.example.memegram

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.localization.AppStrings
import com.example.memegram.localization.LocalStrings

private fun roleDisplayName(role: String, s: AppStrings): String = when (role) {
    "owner" -> s.roleOwner
    "admin" -> s.roleAdmin
    else    -> s.roleMember
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupProfileScreen(
    topBarColor: Color,
    conversationId: String,
    groupName: String,
    onBack: () -> Unit,
    onLeaveSuccess: () -> Unit,
    onNavigateToChat: (conversationId: String, chatName: String) -> Unit = { _, _ -> },
    onNavigateToUserProfile: (userId: String, username: String) -> Unit = { _, _ -> },
    viewModel: GroupProfileViewModel,
    contactsViewModel: ContactsViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val s = LocalStrings.current
    val members by viewModel.filteredMembers.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val myRole by viewModel.myRole.collectAsState()
    val contacts by contactsViewModel.contacts.collectAsState()
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var newMemberKey by remember { mutableStateOf("") }

    var contextMenuMember by remember { mutableStateOf<GroupMemberUI?>(null) }
    var showKickConfirm by remember { mutableStateOf(false) }
    var kickTargetMember by remember { mutableStateOf<GroupMemberUI?>(null) }
    var dmTargetName by remember { mutableStateOf(s.chatFallback) }

    val isAdmin = myRole == "owner" || myRole == "admin"
    val isOwner = myRole == "owner"

    LaunchedEffect(conversationId) {
        viewModel.loadGroup(conversationId)
    }

    val createdChatId by contactsViewModel.chatCreated.collectAsState()
    LaunchedEffect(createdChatId) {
        createdChatId?.let { id ->
            contactsViewModel.clearChatCreated()
            onNavigateToChat(id, dmTargetName)
        }
    }

    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it) }
    }

    // ── Leave confirmation ───────────────────────
    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(s.leaveGroupTitle) },
            text = { Text(s.leaveGroupMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        viewModel.leaveGroup(conversationId, onLeaveSuccess)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(s.leave) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text(s.cancel) }
            }
        )
    }

    // ── Kick confirmation ────────────────────────
    if (showKickConfirm && kickTargetMember != null) {
        val target = kickTargetMember!!
        AlertDialog(
            onDismissRequest = { showKickConfirm = false; kickTargetMember = null },
            title = { Text(s.removeMemberTitle) },
            text = { Text(s.removeMemberMessage(target.user.username ?: s.userFallback)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKickConfirm = false
                        viewModel.kickMember(conversationId, target.user.id)
                        kickTargetMember = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(s.remove) }
            },
            dismissButton = {
                TextButton(onClick = { showKickConfirm = false; kickTargetMember = null }) { Text(s.cancel) }
            }
        )
    }

    // ── Member context menu (long-press) ─────────
    contextMenuMember?.let { member ->
        val isSelf = member.user.id == viewModel.currentUserId
        val canKick = isAdmin && member.role != "owner" && (isOwner || member.role != "admin") && !isSelf
        val canPromote = isAdmin && member.role == "member" && !isSelf
        val canDemote = isOwner && member.role == "admin" && !isSelf
        val isAlreadyContact = contacts.any { it.contactUserId == member.user.id }

        AlertDialog(
            onDismissRequest = { contextMenuMember = null },
            title = { Text(member.user.username ?: s.member) },
            text = {
                Column {
                    if (!isSelf) {
                        TextButton(
                            onClick = {
                                val targetName = member.user.username ?: s.chatFallback
                                contextMenuMember = null
                                dmTargetName = targetName
                                contactsViewModel.startDirectChatByUserId(member.user.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(s.sendDM, modifier = Modifier.fillMaxWidth()) }

                        if (!isAlreadyContact && member.user.userPublicKey != null) {
                            TextButton(
                                onClick = {
                                    contextMenuMember = null
                                    contactsViewModel.addContact(member.user.userPublicKey!!)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(s.addToContacts, modifier = Modifier.fillMaxWidth()) }
                        }
                    }

                    if (canPromote) {
                        TextButton(
                            onClick = {
                                contextMenuMember = null
                                viewModel.updateMemberRole(conversationId, member.user.id, "admin")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(s.makeAdmin, modifier = Modifier.fillMaxWidth()) }
                    }
                    if (canDemote) {
                        TextButton(
                            onClick = {
                                contextMenuMember = null
                                viewModel.updateMemberRole(conversationId, member.user.id, "member")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(s.removeAdminRole, modifier = Modifier.fillMaxWidth()) }
                    }
                    if (canKick) {
                        TextButton(
                            onClick = {
                                contextMenuMember = null
                                kickTargetMember = member
                                showKickConfirm = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(s.removeFromGroup, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { contextMenuMember = null }) { Text(s.cancel) }
            }
        )
    }

    // ── Add member dialog ────────────────────────
    if (showAddMemberDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false; selectedContactId = null },
            title = { Text(s.selectMember) },
            text = {
                val availableContacts = contacts.filter { c ->
                    members.none { m -> m.user.id == c.contactUserId }
                }

                if (availableContacts.isEmpty()) {
                    Text(s.noAvailableContacts, color = Color.Gray)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxHeight(0.6f)) {
                        items(availableContacts, key = { it.contactUserId }) { contact ->
                            val isSelected = selectedContactId == contact.contactUserId
                            val name = contact.profile?.username ?: s.noName

                            ListItem(
                                modifier = Modifier.clickable { selectedContactId = contact.contactUserId },
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    AvatarImage(
                                        mediaId = contact.profile?.avatarMediaId,
                                        size = 36.dp,
                                        fallbackLetter = name.take(1).uppercase(),
                                        backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                        textColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                },
                                trailingContent = {
                                    RadioButton(selected = isSelected, onClick = null)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedContactId?.let { uid ->
                            viewModel.addMemberByUserId(conversationId, uid)
                        }
                        showAddMemberDialog = false
                        selectedContactId = null
                    },
                    enabled = selectedContactId != null
                ) { Text(s.add) }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemberDialog = false; selectedContactId = null }) { Text(s.cancel) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.groupInfo, color = topBarTextColor) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarColor)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(100.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(groupName.take(1).uppercase(), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Text(groupName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(s.membersCount(members.size), color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            if (isAdmin) {
                ListItem(
                    headlineContent = { Text(s.addMember, color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { showAddMemberDialog = true }
                )
            }
            ListItem(
                headlineContent = { Text(s.leaveGroup, color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { showLeaveConfirm = true }
            )

            HorizontalDivider(thickness = 8.dp, color = MaterialTheme.colorScheme.surfaceVariant)

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text(s.searchMembers) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                shape = CircleShape
            )

            if (isLoading && members.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(members, key = { it.user.id }) { member ->
                        val canInteract = member.user.id != viewModel.currentUserId
                        ListItem(
                            headlineContent = { Text(member.user.username ?: s.noName) },
                            supportingContent = { Text(roleDisplayName(member.role, s)) },
                            leadingContent = {
                                AvatarImage(
                                    mediaId = member.user.avatarMediaId,
                                    size = 40.dp,
                                    fallbackLetter = (member.user.username ?: "?").take(1).uppercase(),
                                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                    textColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            },
                            modifier = if (canInteract) {
                                Modifier.combinedClickable(
                                    onClick = {
                                        val name = member.user.username ?: s.noName
                                        onNavigateToUserProfile(member.user.id, name)
                                    },
                                    onLongClick = { contextMenuMember = member }
                                )
                            } else Modifier
                        )
                    }
                }
            }
        }
    }
}