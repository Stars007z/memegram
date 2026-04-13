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
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox

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
    onBack: () -> Unit,
    viewModel: NotificationsViewModel
) {
    val s = LocalStrings.current
    val vibrateStrength  by viewModel.vibrateStrength.collectAsState()
    val ringtoneKey      by viewModel.ringtoneKey.collectAsState()
    val currentVibrate   = NotificationsViewModel.strengthToLabel(vibrateStrength, s)
    val currentRingtone  = NotificationsViewModel.ringtoneKeyToLabel(ringtoneKey, s)
    val topBarTextColor  = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White

    val allData = remember {
        mutableStateListOf<NotifItem>().apply {
            add(NotifItem.Category(1, s.privateChats))
            add(NotifItem.Chat(1, "Neko", false))
            add(NotifItem.Chat(1, "Denis", true))
            add(NotifItem.Chat(1, "Ivan Kopylov", false))
            add(NotifItem.Category(2, s.groups))
            add(NotifItem.Chat(2, "Kotlin Evil", false))
            add(NotifItem.Category(3, s.channels))
            add(NotifItem.Chat(3, "Meme Channel", false))
            add(NotifItem.CallsSettings)
        }
    }

    var muteDialogTarget by remember { mutableStateOf<NotifItem.Chat?>(null) }
    var muteHoursDialog  by remember { mutableStateOf<NotifItem.Chat?>(null) }
    var muteHoursInput   by remember { mutableStateOf("") }
    var showVibrateDialog  by remember { mutableStateOf(false) }
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
        val enableOption  = s.enableNotifications
        val disableForever = s.disableForever
        val disableForTime = s.disableForTime
        val options = if (chat.isMuted)
            listOf(enableOption)
        else
            listOf(disableForever, disableForTime)

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
                                .padding(vertical = 12.sdp),
                            color = if (option == disableForever || option == disableForTime)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                        if (idx < options.lastIndex) HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { muteDialogTarget = null }) { Text(s.cancel) }
            }
        )
    }

    muteHoursDialog?.let { chat ->
        AlertDialog(
            onDismissRequest = { muteHoursDialog = null },
            title = { Text(s.disableForHowLong) },
            text = {
                OutlinedTextField(
                    value = muteHoursInput,
                    onValueChange = { muteHoursInput = it.filter { c -> c.isDigit() } },
                    label = { Text(s.hours) },
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
                }) { Text(s.disable) }
            },
            dismissButton = {
                TextButton(onClick = { muteHoursDialog = null }) { Text(s.cancel) }
            }
        )
    }

    if (showVibrateDialog) {
        val options = NotificationsViewModel.vibrateOptions(s)
        AlertDialog(
            onDismissRequest = { showVibrateDialog = false },
            title = { Text(s.vibrationForCalls) },
            text = {
                Column {
                    options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setVibrateStrength(
                                        NotificationsViewModel.labelToStrength(opt, s)
                                    )
                                    showVibrateDialog = false
                                }
                                .padding(vertical = 10.sdp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentVibrate == opt,
                                onClick = {
                                    viewModel.setVibrateStrength(
                                        NotificationsViewModel.labelToStrength(opt, s)
                                    )
                                    showVibrateDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.sdp))
                            Text(opt)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showVibrateDialog = false }) { Text(s.cancel) }
            }
        )
    }

    if (showRingtoneDialog) {
        val options = NotificationsViewModel.ringtoneOptions(s)
        AlertDialog(
            onDismissRequest = { showRingtoneDialog = false },
            title = { Text(s.ringtone) },
            text = {
                Column {
                    options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setRingtoneKey(
                                        NotificationsViewModel.ringtoneLabelToKey(opt, s)
                                    )
                                    showRingtoneDialog = false
                                }
                                .padding(vertical = 10.sdp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentRingtone == opt,
                                onClick = {
                                    viewModel.setRingtoneKey(
                                        NotificationsViewModel.ringtoneLabelToKey(opt, s)
                                    )
                                    showRingtoneDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.sdp))
                            Text(opt)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRingtoneDialog = false }) { Text(s.cancel) }
            }
        )
    }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                title = { Text(s.notificationsTitle) },
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
                    containerColor = bgColor,
                    titleContentColor = topBarTextColor,
                    navigationIconContentColor = topBarTextColor
                )
            )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.sdp),
            verticalArrangement = Arrangement.spacedBy(4.sdp),
            contentPadding = PaddingValues(vertical = 12.sdp)
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
                        disabledLabel = s.disabled,
                        enabledLabel = s.enabled,
                        onClick = { muteDialogTarget = item }
                    )

                    is NotifItem.CallsSettings -> CallsSettingsBlock(
                        accentColor = topBarColor,
                        vibrate = currentVibrate,
                        ringtone = currentRingtone,
                        callsSectionLabel = s.callsSection,
                        vibrationLabel = s.vibration,
                        ringtoneLabel = s.ringtone,
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
        shape = RoundedCornerShape(12.sdp),
        tonalElevation = 2.sdp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.sdp, vertical = 14.sdp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = category.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.ssp
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
    disabledLabel: String,
    enabledLabel: String,
    onClick: () -> Unit
) {
    val indicatorColor = if (chat.isMuted) Color(0xFFFF3B30) else accentColor
    val statusText = if (chat.isMuted) disabledLabel else enabledLabel

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.sdp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.sdp),
        tonalElevation = 1.sdp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.sdp, vertical = 12.sdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.sdp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Spacer(Modifier.width(12.sdp))
            Text(
                text = chat.name,
                modifier = Modifier.weight(1f),
                fontSize = 15.ssp
            )
            Text(
                text = statusText,
                color = indicatorColor,
                fontSize = 13.ssp,
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
    callsSectionLabel: String,
    vibrationLabel: String,
    ringtoneLabel: String,
    onVibrateClick: () -> Unit,
    onRingtoneClick: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 20.sdp)) {
        Text(
            text = callsSectionLabel,
            fontSize = 12.ssp,
            color = Color(0xFF8E8E93),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.sdp, bottom = 6.sdp)
        )
        Surface(
            shape = RoundedCornerShape(12.sdp),
            tonalElevation = 2.sdp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onVibrateClick)
                        .padding(horizontal = 16.sdp, vertical = 14.sdp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(vibrationLabel, fontSize = 15.ssp)
                    Text(
                        text = vibrate,
                        color = accentColor,
                        fontSize = 15.ssp
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.sdp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRingtoneClick)
                        .padding(horizontal = 16.sdp, vertical = 14.sdp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(ringtoneLabel, fontSize = 15.ssp)
                    Text(
                        text = ringtone,
                        color = accentColor,
                        fontSize = 15.ssp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
