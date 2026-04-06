package com.example.memegram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onGroupCreated: (String) -> Unit,
    vm: ContactsViewModel = koinViewModel()
) {
    val contacts by vm.contacts.collectAsState()
    val isCreating by vm.isCreatingChat.collectAsState()
    val error by vm.error.collectAsState()
    val createdChatId by vm.chatCreated.collectAsState()

    var groupName by remember { mutableStateOf("") }
    val selectedUserIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(createdChatId) {
        createdChatId?.let {
            vm.clearChatCreated()
            onGroupCreated(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Создать группу") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.createGroupChat(groupName, selectedUserIds) },
                        enabled = groupName.isNotBlank() && selectedUserIds.isNotEmpty() && !isCreating
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Создать", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Название группы") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                enabled = !isCreating
            )

            Text(
                text = "Участники (${selectedUserIds.size} выбрано)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )

            if (isCreating) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("У вас пока нет контактов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn {
                    items(contacts, key = { it.contactUserId }) { contact ->
                        val isSelected = selectedUserIds.contains(contact.contactUserId)
                        val name = contact.profile?.username?.takeIf { it.isNotBlank() } ?: contact.contactUserId.take(8)

                        ListItem(
                            modifier = Modifier.clickable {
                                if (isSelected) selectedUserIds.remove(contact.contactUserId)
                                else selectedUserIds.add(contact.contactUserId)
                            },
                            headlineContent = { Text(name) },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp).clip(CircleShape),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(8.dp))
                                }
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = null
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}