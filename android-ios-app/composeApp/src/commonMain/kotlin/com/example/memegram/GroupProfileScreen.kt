package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupProfileScreen(
    topBarColor: Color,
    conversationId: String,
    groupName: String,
    onBack: () -> Unit,
    onLeaveSuccess: () -> Unit,
    viewModel: GroupProfileViewModel,
    contactsViewModel: ContactsViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val members by viewModel.filteredMembers.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val contacts by contactsViewModel.contacts.collectAsState()
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var newMemberKey by remember { mutableStateOf("") }

    LaunchedEffect(conversationId) {
        viewModel.loadGroup(conversationId)
    }

    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it) }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Покинуть группу?") },
            text = { Text("Вы больше не будете получать сообщения из этого чата.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        viewModel.leaveGroup(conversationId, onLeaveSuccess)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Покинуть") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showAddMemberDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false; selectedContactId = null },
            title = { Text("Выберите участника") },
            text = {
                val availableContacts = contacts.filter { c ->
                    members.none { m -> m.user.id == c.contactUserId }
                }

                if (availableContacts.isEmpty()) {
                    Text("Нет доступных контактов для добавления", color = Color.Gray)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxHeight(0.6f)) {
                        items(availableContacts, key = { it.contactUserId }) { contact ->
                            val isSelected = selectedContactId == contact.contactUserId
                            val name = contact.profile?.username ?: "Без имени"

                            ListItem(
                                modifier = Modifier.clickable { selectedContactId = contact.contactUserId },
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
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
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemberDialog = false; selectedContactId = null }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Информация о группе", color = topBarTextColor) },
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
                Text("${members.size} участников", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Добавить участника", color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { showAddMemberDialog = true }
            )
            ListItem(
                headlineContent = { Text("Покинуть группу", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { showLeaveConfirm = true }
            )

            HorizontalDivider(thickness = 8.dp, color = MaterialTheme.colorScheme.surfaceVariant)

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text("Поиск участников...") },
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
                        ListItem(
                            headlineContent = { Text(member.user.username ?: "Без имени") },
                            supportingContent = { Text(if (member.role == "admin") "Администратор" else "Участник") },
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (member.user.username ?: "?").take(1).uppercase(),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}