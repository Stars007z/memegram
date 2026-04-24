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
import com.example.memegram.localization.AppStrings
import com.example.memegram.localization.LocalStrings
import com.example.memegram.notifications.NotificationPrefs
import com.example.memegram.utils.ImageTopAppBarBox
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: NotificationsViewModel,
) {
    val s = LocalStrings.current
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White

    val uiState by viewModel.uiState.collectAsState()
    val previewEnabled by viewModel.previewEnabled.collectAsState()
    val vibrationStrength by viewModel.vibrationStrength.collectAsState()

    var privateExpanded by remember { mutableStateOf(false) }
    var groupsExpanded by remember { mutableStateOf(false) }

    var muteDialogTarget by remember { mutableStateOf<ChatModel?>(null) }
    var muteHoursDialog by remember { mutableStateOf<ChatModel?>(null) }
    var muteHoursInput by remember { mutableStateOf("") }

    muteDialogTarget?.let { chat ->
        val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val isMuted = chat.muteUntil > nowMs
        val options = if (isMuted)
            listOf(s.enableNotifications)
        else
            listOf(s.disableForever, s.disableForTime)

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
                                    if (isMuted) {
                                        viewModel.unmute(chat.conversationId)
                                    } else {
                                        when (idx) {
                                            0 -> viewModel.toggleMute(chat.conversationId, chat.muteUntil)
                                            1 -> muteHoursDialog = chat
                                        }
                                    }
                                    muteDialogTarget = null
                                }
                                .padding(vertical = 12.sdp),
                            color = if (isMuted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        if (idx < options.lastIndex) HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { muteDialogTarget = null }) { Text(s.cancel) }
            },
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
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val hours = muteHoursInput.toIntOrNull()
                    if (hours != null && hours > 0) {
                        viewModel.muteForHours(chat.conversationId, hours)
                    }
                    muteHoursInput = ""
                    muteHoursDialog = null
                }) { Text(s.disable) }
            },
            dismissButton = {
                TextButton(onClick = { muteHoursDialog = null }) { Text(s.cancel) }
            },
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
                                tint = topBarTextColor,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bgColor,
                        titleContentColor = topBarTextColor,
                        navigationIconContentColor = topBarTextColor,
                    ),
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.sdp),
            verticalArrangement = Arrangement.spacedBy(4.sdp),
            contentPadding = PaddingValues(vertical = 12.sdp),
        ) {
            // ── Global preferences ────────────────────────────────────
            item("pref_preview") {
                PreferenceRow(
                    title = s.notificationPreview,
                    subtitle = s.notificationPreviewDesc,
                    checked = previewEnabled,
                    onCheckedChange = viewModel::setPreviewEnabled,
                )
            }
            item("pref_vibration") {
                VibrationRow(
                    strings = s,
                    value = vibrationStrength,
                    onValueChange = viewModel::setVibrationStrength,
                )
            }

            item("spacer1") { Spacer(Modifier.height(8.sdp)) }

            // ── Private chats ─────────────────────────────────────────
            item("cat_private") {
                CategoryItem(
                    name = s.privateChats,
                    count = uiState.privateChats.size,
                    isExpanded = privateExpanded,
                    onClick = { privateExpanded = !privateExpanded },
                )
            }
            if (privateExpanded) {
                if (uiState.privateChats.isEmpty()) {
                    item("private_empty") { EmptyChatsRow(s.noChatsToConfigure) }
                } else {
                    items(
                        uiState.privateChats,
                        key = { "private_${it.conversationId}" },
                    ) { chat ->
                        ChatNotifItem(
                            chat = chat,
                            accentColor = topBarColor,
                            disabledLabel = s.disabled,
                            enabledLabel = s.enabled,
                            onClick = { muteDialogTarget = chat },
                        )
                    }
                }
            }

            // ── Groups ────────────────────────────────────────────────
            item("cat_groups") {
                CategoryItem(
                    name = s.groups,
                    count = uiState.groups.size,
                    isExpanded = groupsExpanded,
                    onClick = { groupsExpanded = !groupsExpanded },
                )
            }
            if (groupsExpanded) {
                if (uiState.groups.isEmpty()) {
                    item("groups_empty") { EmptyChatsRow(s.noChatsToConfigure) }
                } else {
                    items(
                        uiState.groups,
                        key = { "group_${it.conversationId}" },
                    ) { chat ->
                        ChatNotifItem(
                            chat = chat,
                            accentColor = topBarColor,
                            disabledLabel = s.disabled,
                            enabledLabel = s.enabled,
                            onClick = { muteDialogTarget = chat },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.sdp),
        tonalElevation = 2.sdp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.sdp, vertical = 12.sdp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.ssp, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    fontSize = 12.ssp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun VibrationRow(
    strings: AppStrings,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val label = when (value) {
        0 -> strings.vibrationOff
        1 -> strings.vibrationLight
        2 -> strings.vibrationNormal
        3 -> strings.vibrationStrong
        else -> strings.vibrationNormal
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.sdp),
        tonalElevation = 2.sdp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.sdp, vertical = 12.sdp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = strings.vibration, fontSize = 15.ssp, fontWeight = FontWeight.Medium)
                Text(
                    text = label,
                    fontSize = 13.ssp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = NotificationPrefs.VIBRATION_MIN.toFloat()..NotificationPrefs.VIBRATION_MAX.toFloat(),
                steps = NotificationPrefs.VIBRATION_MAX - NotificationPrefs.VIBRATION_MIN - 1,
            )
        }
    }
}

@Composable
private fun CategoryItem(
    name: String,
    count: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "arrow",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.sdp),
        tonalElevation = 2.sdp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.sdp, vertical = 14.sdp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (count > 0) "$name  ·  $count" else name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.ssp,
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation),
                tint = Color.Gray,
            )
        }
    }
}

@Composable
private fun ChatNotifItem(
    chat: ChatModel,
    accentColor: Color,
    disabledLabel: String,
    enabledLabel: String,
    onClick: () -> Unit,
) {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val isMuted = chat.muteUntil > nowMs
    val indicatorColor = if (isMuted) Color(0xFFFF3B30) else accentColor
    val statusText = if (isMuted) disabledLabel else enabledLabel

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.sdp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.sdp),
        tonalElevation = 1.sdp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.sdp, vertical = 12.sdp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.sdp)
                    .clip(CircleShape)
                    .background(indicatorColor),
            )
            Spacer(Modifier.width(12.sdp))
            Text(
                text = chat.name,
                modifier = Modifier.weight(1f),
                fontSize = 15.ssp,
            )
            Text(
                text = statusText,
                color = indicatorColor,
                fontSize = 13.ssp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun EmptyChatsRow(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.sdp),
        shape = RoundedCornerShape(10.sdp),
        tonalElevation = 1.sdp,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.sdp, vertical = 12.sdp),
            fontSize = 14.ssp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
