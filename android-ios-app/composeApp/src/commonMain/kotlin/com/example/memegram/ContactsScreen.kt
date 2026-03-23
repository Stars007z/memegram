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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    onChatClick: (String) -> Unit,
    viewModel: ContactsViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val contacts by viewModel.contacts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isAdding by viewModel.isAdding.collectAsState()
    val error by viewModel.error.collectAsState()
    val addSuccess by viewModel.addSuccess.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var publicKeyInput by remember { mutableStateOf("") }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }

    // Закрываем диалог добавления при успехе
    LaunchedEffect(addSuccess) {
        if (addSuccess) {
            showAddDialog = false
            publicKeyInput = ""
            viewModel.resetAddSuccess()
        }
    }

    // Диалог ошибки
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("Ошибка") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } }
        )
    }

    // Диалог добавления контакта
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!isAdding) showAddDialog = false },
            title = { Text("Новый контакт") },
            text = {
                Column {
                    Text(
                        "Введите публичный ключ пользователя",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = publicKeyInput,
                        onValueChange = { publicKeyInput = it },
                        label = { Text("Публичный ключ") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
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
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text("Добавить")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddDialog = false; publicKeyInput = "" },
                    enabled = !isAdding
                ) { Text("Отмена") }
            }
        )
    }

    // Диалог удаления
    pendingRemoveId?.let { userId ->
        val name = contacts.find { it.contactUserId == userId }
            ?.profile?.username ?: (userId.take(8) + "...")
        AlertDialog(
            onDismissRequest = { pendingRemoveId = null },
            title = { Text("Удалить контакт?") },
            text = { Text("Удалить $name из контактов?") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.removeContact(userId); pendingRemoveId = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveId = null }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Контакты") },
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
                    containerColor = topBarColor,
                    titleContentColor = topBarTextColor,
                    actionIconContentColor = topBarTextColor,
                    navigationIconContentColor = topBarTextColor
                )
            )
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
                            modifier = Modifier.size(64.dp),
                            tint = Color.LightGray
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Нет контактов",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Нажмите + чтобы добавить контакт\nпо публичному ключу",
                            fontSize = 13.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }

                else -> {
                    // Секция избранных
                    val favorites = contacts.filter { it.isFavorite }
                    val others = contacts.filter { !it.isFavorite }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (favorites.isNotEmpty()) {
                            item {
                                SectionHeader("⭐ Избранные", topBarColor)
                            }
                            items(favorites, key = { it.contactUserId }) { entry ->
                                ContactItem(
                                    entry = entry,
                                    accentColor = topBarColor,
                                    onChatClick = {
                                        onChatClick(entry.profile?.username ?: entry.contactUserId)
                                    },
                                    onFavoriteToggle = { viewModel.toggleFavorite(entry.contactUserId) },
                                    onRemove = { pendingRemoveId = entry.contactUserId }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }

                        if (others.isNotEmpty()) {
                            item {
                                SectionHeader("Все контакты", topBarColor)
                            }
                            items(others, key = { it.contactUserId }) { entry ->
                                ContactItem(
                                    entry = entry,
                                    accentColor = topBarColor,
                                    onChatClick = {
                                        onChatClick(entry.profile?.username ?: entry.contactUserId)
                                    },
                                    onFavoriteToggle = { viewModel.toggleFavorite(entry.contactUserId) },
                                    onRemove = { pendingRemoveId = entry.contactUserId }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.dp),
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
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun ContactItem(
    entry: ContactEntry,
    accentColor: Color,
    onChatClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onRemove: () -> Unit
) {
    val displayName = entry.profile?.username
        ?.takeIf { it.isNotBlank() }
        ?: "@${entry.contactUserId.take(8)}"
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Аватар
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (entry.isFavorite) accentColor.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayName.take(1).uppercase(),
                color = if (entry.isFavorite) accentColor else Color.Gray,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        Spacer(Modifier.width(14.dp))

        // Имя + bio
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            entry.profile?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(
                    text = bio,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }
        }

        // Кнопка чата
        IconButton(onClick = onChatClick) {
            Icon(Icons.Default.ChatBubbleOutline, null, tint = accentColor)
        }

        // Меню
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
                        Text(if (entry.isFavorite) "Убрать из избранного" else "В избранное")
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
                    text = {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PersonRemove,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { onRemove(); showMenu = false }
                )
            }
        }
    }
}