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
    onBlackListClick: () -> Unit
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White

    var showAutoDeleteMsgDialog by remember { mutableStateOf(false) }
    var showAutoDeleteAccDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    var autoDeleteMsgOption by remember { mutableStateOf<String?>(null) }
    var autoDeleteAccOption by remember { mutableStateOf<String?>(null) }

    val autoDeleteOptions = listOf("1 месяц", "3 месяца", "6 месяцев", "1 год")

    if (showAutoDeleteMsgDialog) {
        AlertDialog(
            onDismissRequest = { showAutoDeleteMsgDialog = false },
            title = { Text("Авто-удаление сообщений") },
            text = {
                Column {
                    autoDeleteOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autoDeleteMsgOption = option
                                    showAutoDeleteMsgDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoDeleteMsgOption == option,
                                onClick = {
                                    autoDeleteMsgOption = option
                                    showAutoDeleteMsgDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(option)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAutoDeleteMsgDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showAutoDeleteAccDialog) {
        AlertDialog(
            onDismissRequest = { showAutoDeleteAccDialog = false },
            title = { Text("Авто-удаление аккаунта") },
            text = {
                Column {
                    autoDeleteOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autoDeleteAccOption = option
                                    showAutoDeleteAccDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoDeleteAccOption == option,
                                onClick = {
                                    autoDeleteAccOption = option
                                    showAutoDeleteAccDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(option)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAutoDeleteAccDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text("Удалить аккаунт?") },
            text = {
                Text("Это действие необратимо. Все ваши данные и сообщения будут навсегда удалены.")
            },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteAccountDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = topBarTextColor
                        )
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
            SectionLabel("КОНТАКТЫ")
            PrivacyItem(
                title = "Чёрный список",
                subtitle = "Заблокированные пользователи",
                accentColor = topBarColor,
                showArrow = true,
                onClick = onBlackListClick
            )

            Spacer(Modifier.height(8.dp))

            SectionLabel("СООБЩЕНИЯ")
            PrivacyItem(
                title = "Авто-удаление сообщений",
                subtitle = autoDeleteMsgOption ?: "Выключено",
                accentColor = topBarColor,
                showArrow = true,
                onClick = { showAutoDeleteMsgDialog = true }
            )

            Spacer(Modifier.height(8.dp))

            SectionLabel("АККАУНТ")
            PrivacyItem(
                title = "Авто-удаление аккаунта",
                subtitle = autoDeleteAccOption ?: "Выключено",
                accentColor = topBarColor,
                showArrow = true,
                onClick = { showAutoDeleteAccDialog = true }
            )

            Spacer(Modifier.height(4.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDeleteAccountDialog = true },
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "Удалить аккаунт",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}


@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Color(0xFF8E8E93),
        fontWeight = FontWeight.Bold,
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = accentColor
                )
            }
            if (showArrow) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.Gray
                )
            }
        }
    }
}