package com.example.memegram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    onBlackListClick: () -> Unit,
    onAccountDeleted: () -> Unit,
    viewModel: PrivacyViewModel
) {
    val topBarTextColor       = if (topBarColor.luminance() < 0.5f) Color.White else Color.Black
    val profileVisibleTo      by viewModel.profileVisibleTo.collectAsState()
    val lastActiveVisibleTo   by viewModel.lastActiveVisibleTo.collectAsState()
    val autoDeleteDays        by viewModel.autoDeleteDays.collectAsState()
    val accountDeleted        by viewModel.accountDeleted.collectAsState()
    val isLoading             by viewModel.isLoading.collectAsState()
    val error                 by viewModel.error.collectAsState()

    LaunchedEffect(accountDeleted) { if (accountDeleted) onAccountDeleted() }

    var showProfileVisDialog    by remember { mutableStateOf(false) }
    var showLastActiveVisDialog by remember { mutableStateOf(false) }
    var showAutoDeleteAccDialog by remember { mutableStateOf(false) }
    var showAutoDeleteMsgDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var autoDeleteMsgOption     by remember { mutableStateOf<String?>(null) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("Ошибка") },
            text  = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } }
        )
    }

    if (showProfileVisDialog) {
        PrivacyChoiceDialog(
            title   = "Кто видит мой профиль",
            options = PrivacyViewModel.visibilityOptions,
            current = PrivacyViewModel.visibilityLabel(profileVisibleTo),
            onSelect  = { viewModel.setProfileVisibleTo(PrivacyViewModel.visibilityValue(it)); showProfileVisDialog = false },
            onDismiss = { showProfileVisDialog = false }
        )
    }

    if (showLastActiveVisDialog) {
        PrivacyChoiceDialog(
            title   = "Кто видит время активности",
            options = PrivacyViewModel.visibilityOptions,
            current = PrivacyViewModel.visibilityLabel(lastActiveVisibleTo),
            onSelect  = { viewModel.setLastActiveVisibleTo(PrivacyViewModel.visibilityValue(it)); showLastActiveVisDialog = false },
            onDismiss = { showLastActiveVisDialog = false }
        )
    }

    if (showAutoDeleteAccDialog) {
        PrivacyChoiceDialog(
            title   = "Удалить аккаунт через",
            options = PrivacyViewModel.autoDeleteOptions,
            current = PrivacyViewModel.daysLabel(autoDeleteDays),
            onSelect  = { viewModel.setAutoDeleteDays(PrivacyViewModel.daysValue(it)); showAutoDeleteAccDialog = false },
            onDismiss = { showAutoDeleteAccDialog = false }
        )
    }

    if (showAutoDeleteMsgDialog) {
        PrivacyChoiceDialog(
            title   = "Авто-удаление сообщений",
            options = listOf("Выкл", "1 день", "1 неделя", "1 месяц"),
            current = autoDeleteMsgOption ?: "Выкл",
            onSelect  = { autoDeleteMsgOption = it; showAutoDeleteMsgDialog = false },
            onDismiss = { showAutoDeleteMsgDialog = false }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text("Удалить аккаунт?") },
            text  = { Text("Это действие необратимо. Все данные будут удалены.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteAccountDialog = false; viewModel.deleteAccount() },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Конфиденциальность") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarColor,
                    titleContentColor = topBarTextColor,
                    navigationIconContentColor = topBarTextColor
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionLabel("Контакты")
            PrivacyItem(
                title = "Чёрный список",
                subtitle = "Управление",
                accentColor = topBarColor, showArrow = true,
                onClick = onBlackListClick
            )

            Spacer(Modifier.height(4.dp))

            SectionLabel("Приватность")
            PrivacyItem(
                title = "Кто видит мой профиль",
                subtitle = PrivacyViewModel.visibilityLabel(profileVisibleTo),
                accentColor = topBarColor, showArrow = true,
                onClick = { showProfileVisDialog = true }
            )
            PrivacyItem(
                title = "Кто видит время активности",
                subtitle = PrivacyViewModel.visibilityLabel(lastActiveVisibleTo),
                accentColor = topBarColor, showArrow = true,
                onClick = { showLastActiveVisDialog = true }
            )

            Spacer(Modifier.height(4.dp))

            SectionLabel("Авто-удаление")
            PrivacyItem(
                title = "Авто-удаление сообщений",
                subtitle = autoDeleteMsgOption ?: "Выкл",
                accentColor = topBarColor, showArrow = true,
                onClick = { showAutoDeleteMsgDialog = true }
            )
            PrivacyItem(
                title = "Удалить аккаунт через",
                subtitle = PrivacyViewModel.daysLabel(autoDeleteDays),
                accentColor = topBarColor, showArrow = true,
                onClick = { showAutoDeleteAccDialog = true }
            )

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isLoading) {
                    showDeleteAccountDialog = true
                },
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            "Удалить аккаунт",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyChoiceDialog(
    title: String,
    options: List<String>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == option, onClick = { onSelect(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 12.sp, color = Color(0xFF8E8E93), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun PrivacyItem(
    title: String,
    subtitle: String,
    accentColor: Color,
    showArrow: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 13.sp, color = accentColor)
            }
            if (showArrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
        }
    }
}