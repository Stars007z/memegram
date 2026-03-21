package com.example.memegram

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class NotifItem {
    data class Category(
        val id: Int,
        val name: String,
        var isExpanded: Boolean = false
    ) : NotifItem()

    data class Chat(
        val parentId: Int,
        val name: String,
        var isMuted: Boolean = false
    ) : NotifItem()

    data object CallsSettings : NotifItem()
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    topBarColor: Color,
    onBack: () -> Unit
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val allData = remember {
        mutableStateListOf<NotifItem>().apply {
            add(NotifItem.Category(1, "Личные чаты"))
            add(NotifItem.Chat(1, "Neko", false))
            add(NotifItem.Chat(1, "Denis", true))
            add(NotifItem.Chat(1, "Ivan Kopylov", false))
            add(NotifItem.Category(2, "Группы"))
            add(NotifItem.Chat(2, "Kotlin Evil", false))
            add(NotifItem.Category(3, "Каналы"))
            add(NotifItem.Chat(3, "Meme Channel", false))
            add(NotifItem.CallsSettings)
        }
    }

    var muteDialogTarget by remember { mutableStateOf<NotifItem.Chat?>(null) }
    var muteHoursDialog by remember { mutableStateOf<NotifItem.Chat?>(null) }
    var muteHoursInput by remember { mutableStateOf("") }
    var currentVibrate by remember { mutableStateOf("Medium") }
    var currentRingtone by remember { mutableStateOf("Default") }
    var showVibrateDialog by remember { mutableStateOf(false) }
    var showRingtoneDialog by remember { mutableStateOf(false) }

    val displayData = remember(allData.toList()) {
        val list = mutableListOf<NotifItem>()
        var expandedCategoryId = -1
        for (item in allData) {
            when (item) {
                is NotifItem.Category -> {
                    list.add(item)
                    expandedCategoryId = if (item.isExpanded) item.id else -1
                }
                is NotifItem.Chat -> {
                    if (item.parentId == expandedCategoryId) list.add(item)
                }
                is NotifItem.CallsSettings -> list.add(item)
            }
        }
        list
    }


    muteDialogTarget?.let { chat ->
        val options = if (chat.isMuted)
            listOf("Включить уведомления")
        else
            listOf("Отключить навсегда", "Отключить на время...")

        AlertDialog(
            onDismissRequest = { muteDialogTarget = null },
            title = { Text(chat.name) },
            text = {
                Column {
                    options.forEachIndexed { idx, option ->
                        Text(
                            text = option,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val chatIdx = allData.indexOfFirst {
                                        it is NotifItem.Chat && it.name == chat.name && it.parentId == chat.parentId
                                    }
                                    if (chatIdx != -1) {
                                        val c = allData[chatIdx] as NotifItem.Chat
                                        when (idx) {
                                            0 -> if (chat.isMuted) {
                                                allData[chatIdx] = c.copy(isMuted = false)
                                            } else {
                                                allData[chatIdx] = c.copy(isMuted = true)
                                            }
                                            1 -> { muteHoursDialog = chat }
                                        }
                                    }
                                    muteDialogTarget = null
                                }
                                .padding(vertical = 12.dp),
                            color = if (option.startsWith("Отключить")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                        if (idx < options.lastIndex) HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { muteDialogTarget = null }) { Text("Отмена") }
            }
        )
    }

    muteHoursDialog?.let { chat ->
        AlertDialog(
            onDismissRequest = { muteHoursDialog = null },
            title = { Text("Отключить на сколько часов?") },
            text = {
                OutlinedTextField(
                    value = muteHoursInput,
                    onValueChange = { muteHoursInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Часы") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val hours = muteHoursInput.toIntOrNull()
                    if (hours != null && hours > 0) {
                        val chatIdx = allData.indexOfFirst {
                            it is NotifItem.Chat && it.name == chat.name && it.parentId == chat.parentId
                        }
                        if (chatIdx != -1) {
                            allData[chatIdx] = (allData[chatIdx] as NotifItem.Chat).copy(isMuted = true)
                        }
                    }
                    muteHoursInput = ""
                    muteHoursDialog = null
                }) { Text("Отключить") }
            },
            dismissButton = {
                TextButton(onClick = { muteHoursDialog = null }) { Text("Отмена") }
            }
        )
    }

    if (showVibrateDialog) {
        val options = listOf("Выкл", "Короткая", "Средняя", "Длинная")
        AlertDialog(
            onDismissRequest = { showVibrateDialog = false },
            title = { Text("Вибрация для звонков") },
            text = {
                Column {
                    options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentVibrate = opt
                                    showVibrateDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentVibrate == opt,
                                onClick = { currentVibrate = opt; showVibrateDialog = false }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(opt)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showVibrateDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showRingtoneDialog) {
        val options = listOf("По умолчанию", "Классический", "Цифровой", "Без звука")
        AlertDialog(
            onDismissRequest = { showRingtoneDialog = false },
            title = { Text("Мелодия звонка") },
            text = {
                Column {
                    options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentRingtone = opt
                                    showRingtoneDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentRingtone == opt,
                                onClick = { currentRingtone = opt; showRingtoneDialog = false }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(opt)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRingtoneDialog = false }) { Text("Отмена") }
            }
        )
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(displayData, key = { item ->
                when (item) {
                    is NotifItem.Category -> "cat_${item.id}"
                    is NotifItem.Chat -> "chat_${item.parentId}_${item.name}"
                    is NotifItem.CallsSettings -> "calls"
                }
            }) { item ->
                when (item) {
                    is NotifItem.Category -> CategoryItem(
                        category = item,
                        accentColor = topBarColor,
                        onClick = {
                            val idx = allData.indexOfFirst {
                                it is NotifItem.Category && it.id == item.id
                            }
                            if (idx != -1) {
                                val cat = allData[idx] as NotifItem.Category
                                allData.forEachIndexed { i, it ->
                                    if (it is NotifItem.Category && it.id != item.id) {
                                        allData[i] = it.copy(isExpanded = false)
                                    }
                                }
                                allData[idx] = cat.copy(isExpanded = !cat.isExpanded)
                            }
                        }
                    )

                    is NotifItem.Chat -> ChatNotifItem(
                        chat = item,
                        accentColor = topBarColor,
                        onClick = { muteDialogTarget = item }
                    )

                    is NotifItem.CallsSettings -> CallsSettingsBlock(
                        accentColor = topBarColor,
                        vibrate = currentVibrate,
                        ringtone = currentRingtone,
                        onVibrateClick = { showVibrateDialog = true },
                        onRingtoneClick = { showRingtoneDialog = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(
    category: NotifItem.Category,
    accentColor: Color,
    onClick: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (category.isExpanded) 180f else 0f,
        label = "arrow"
    )

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
            Text(
                text = category.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation),
                tint = Color.Gray
            )
        }
    }
}

@Composable
private fun ChatNotifItem(
    chat: NotifItem.Chat,
    accentColor: Color,
    onClick: () -> Unit
) {
    val indicatorColor = if (chat.isMuted) Color(0xFFFF3B30) else accentColor
    val statusText = if (chat.isMuted) "Отключено" else "Включено"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = chat.name,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp
            )
            Text(
                text = statusText,
                color = indicatorColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CallsSettingsBlock(
    accentColor: Color,
    vibrate: String,
    ringtone: String,
    onVibrateClick: () -> Unit,
    onRingtoneClick: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = "ЗВОНКИ",
            fontSize = 12.sp,
            color = Color(0xFF8E8E93),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onVibrateClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Вибрация", fontSize = 15.sp)
                    Text(
                        text = vibrate,
                        color = accentColor,
                        fontSize = 15.sp
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRingtoneClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Мелодия звонка", fontSize = 15.sp)
                    Text(
                        text = ringtone,
                        color = accentColor,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}