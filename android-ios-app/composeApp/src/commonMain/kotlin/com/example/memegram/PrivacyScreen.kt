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
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    onBlackListClick: () -> Unit,
    onAccountDeleted: () -> Unit,
    viewModel: PrivacyViewModel
) {
    val s = LocalStrings.current
    val topBarTextColor       = if (topBarColor.luminance() < 0.5f) Color.White else Color.Black
    val autoDeleteDays        by viewModel.autoDeleteDays.collectAsState()
    val accountDeleted        by viewModel.accountDeleted.collectAsState()
    val isLoading             by viewModel.isLoading.collectAsState()
    val error                 by viewModel.error.collectAsState()
    val nsfwFilterEnabled     by viewModel.nsfwFilterEnabled.collectAsState()
    val nsfwModelState        by viewModel.nsfwModelState.collectAsState()
    val nsfwModelSize         by viewModel.nsfwModelSize.collectAsState()
    val nsfwSupported         = viewModel.nsfwSupported

    LaunchedEffect(accountDeleted) { if (accountDeleted) onAccountDeleted() }

    var showAutoDeleteAccDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(s.error) },
            text  = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(s.ok) } }
        )
    }

    if (showAutoDeleteAccDialog) {
        PrivacyChoiceDialog(
            title   = s.deleteAccountAfter,
            options = PrivacyViewModel.autoDeleteOptions(s),
            current = PrivacyViewModel.daysLabel(autoDeleteDays, s),
            onSelect  = { viewModel.setAutoDeleteDays(PrivacyViewModel.daysValue(it, s)); showAutoDeleteAccDialog = false },
            onDismiss = { showAutoDeleteAccDialog = false },
            cancelLabel = s.cancel
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text(s.deleteAccountTitle) },
            text  = { Text(s.deleteAccountWarning) },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteAccountDialog = false; viewModel.deleteAccount() },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(s.delete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) { Text(s.cancel) }
            }
        )
    }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                title = { Text(s.privacyTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.sdp, vertical = 12.sdp),
            verticalArrangement = Arrangement.spacedBy(8.sdp)
        ) {
            SectionLabel(s.contactsTitle)
            PrivacyItem(
                title = s.blackList,
                subtitle = s.manage,
                accentColor = topBarColor, showArrow = true,
                onClick = onBlackListClick
            )

            SectionLabel(s.autoDeleteSection)
            PrivacyItem(
                title = s.deleteAccountAfter,
                subtitle = PrivacyViewModel.daysLabel(autoDeleteDays, s),
                accentColor = topBarColor, showArrow = true,
                onClick = { showAutoDeleteAccDialog = true }
            )

            if (nsfwSupported) {
                SectionLabel(s.privacySection)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.sdp),
                    tonalElevation = 2.sdp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.sdp, vertical = 14.sdp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = s.nsfwFilter,
                                    fontSize = 15.ssp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = s.nsfwFilterDescription,
                                    fontSize = 12.ssp,
                                    color = Color.Gray,
                                )
                            }
                            Spacer(Modifier.width(12.sdp))
                            Switch(
                                checked = nsfwFilterEnabled,
                                enabled = nsfwModelState == ModelDownloadState.Ready,
                                onCheckedChange = viewModel::setNsfwFilterEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = topBarColor,
                                    checkedTrackColor = topBarColor.copy(alpha = 0.4f)
                                )
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.sdp))

                        ModelDownloadRow(
                            state = nsfwModelState,
                            modelSize = nsfwModelSize,
                            accent = topBarColor,
                            strings = s,
                            onDownload = viewModel::downloadNsfwModel,
                            onCancel = viewModel::cancelNsfwDownload,
                            onDelete = viewModel::deleteNsfwModel,
                            title = s.nsfwModel,
                            description = s.nsfwModelDescription,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.sdp))

            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isLoading) {
                    showDeleteAccountDialog = true
                },
                shape = RoundedCornerShape(12.sdp),
                tonalElevation = 2.sdp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.sdp, vertical = 16.sdp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.sdp), strokeWidth = 2.sdp)
                    } else {
                        Text(
                            s.deleteAccountAction,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.ssp
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
    onDismiss: () -> Unit,
    cancelLabel: String
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
                            .padding(vertical = 10.sdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == option, onClick = { onSelect(option) })
                        Spacer(Modifier.width(8.sdp))
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 12.ssp, color = Color(0xFF8E8E93), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.sdp, bottom = 2.sdp)
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
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.ssp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 13.ssp, color = accentColor)
            }
            if (showArrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
        }
    }
}
