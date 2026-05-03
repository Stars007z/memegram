package com.example.memegram

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.memegram.data.models.PendingDeviceRegistration
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    onBack: () -> Unit,
    onNavigateToScanQr: () -> Unit,
    vm: LinkedDevicesViewModel = koinViewModel()
) {
    val s = LocalStrings.current
    val devices        by vm.devices.collectAsState()
    val pending        by vm.pendingAdditions.collectAsState()
    val qrPayload      by vm.qrPayload.collectAsState()
    val isLoading      by vm.isLoading.collectAsState()
    val error          by vm.error.collectAsState()
    val successMessage by vm.successMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.load() }

    error?.let { msg ->
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(msg)
            vm.clearError()
        }
    }
    successMessage?.let {
        LaunchedEffect(it) {
            snackbarHostState.showSnackbar(it)
            vm.clearSuccess()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(s.linkedDevicesTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = s.back)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading && devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.sdp),
            verticalArrangement = Arrangement.spacedBy(12.sdp)
        ) {
            // ── Add device ────────────────────────────────────────────
            item {
                Button(
                    onClick = { vm.initAddDevice() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.sdp)
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(Modifier.width(8.sdp))
                    Text(s.addDeviceByQr)
                }
            }

            // ── Pending confirmation ──────────────────────────────────
            if (pending.isNotEmpty()) {
                item {
                    Text(
                        s.pendingConfirmation,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.sdp)
                    )
                }
                items(pending) { reg ->
                    PendingDeviceCard(
                        registration = reg,
                        declineLabel = s.decline,
                        allowLabel = s.allow,
                        canConfirm = reg.status == "awaiting_confirmation",
                        onConfirm  = { vm.confirmAddition(reg.registrationId, true) },
                        onReject   = { vm.confirmAddition(reg.registrationId, false) }
                    )
                }
            }

            // ── Device list ───────────────────────────────────────────
            item {
                Text(
                    s.myDevices,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.sdp)
                )
            }
            items(devices) { device ->
                DeviceCard(
                    device   = device,
                    thisDeviceLabel = s.thisDevice,
                    revokedLabel = s.revoked,
                    revokeLabel = s.revoke,
                    revokeTitle = s.revokeDeviceTitle,
                    revokeMessage = s.revokeDeviceMessage(device.name),
                    cancelLabel = s.cancel,
                    onRevoke = { if (!device.isCurrentDevice) vm.revokeDevice(device.serverId) }
                )
            }
        }
    }

    // ── QR dialog ─────────────────────────────────────────────────────
    qrPayload?.let { payload ->
        QrDialog(
            payload = payload,
            addDeviceLabel = s.addDevice,
            copyCodeHint = s.copyCodeHint,
            copyCodeLabel = s.copyCode,
            codeCopiedLabel = s.codeCopied,
            scanQrInsteadLabel = s.scanQrInstead,
            closeLabel = s.close,
            onDismiss = { vm.clearQr() },
            onNavigateToScan = {
                vm.clearQr()
                onNavigateToScanQr()
            }
        )
    }
}

@Composable
private fun DeviceCard(
    device: DeviceUiModel,
    thisDeviceLabel: String,
    revokedLabel: String,
    revokeLabel: String,
    revokeTitle: String,
    revokeMessage: String,
    cancelLabel: String,
    onRevoke: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.sdp),
        colors   = CardDefaults.cardColors(
            containerColor = if (device.isCurrentDevice)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.sdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (device.type == "primary") Icons.Default.PhoneAndroid
                else Icons.Default.Devices,
                contentDescription = null,
                tint = if (device.isCurrentDevice)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.sdp)
            )
            Spacer(Modifier.width(12.sdp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, fontWeight = FontWeight.SemiBold)
                    if (device.isCurrentDevice) {
                        Spacer(Modifier.width(6.sdp))
                        Badge { Text(thisDeviceLabel) }
                    }
                }
                Text(
                    device.type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!device.isActive) {
                    Text(
                        revokedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (!device.isCurrentDevice && device.isActive) {
                var showConfirm by remember { mutableStateOf(false) }
                IconButton(onClick = { showConfirm = true }) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = revokeLabel,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                if (showConfirm) {
                    AlertDialog(
                        onDismissRequest = { showConfirm = false },
                        title   = { Text(revokeTitle) },
                        text    = { Text(revokeMessage) },
                        confirmButton = {
                            TextButton(onClick = { showConfirm = false; onRevoke() }) {
                                Text(revokeLabel, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirm = false }) { Text(cancelLabel) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingDeviceCard(
    registration: PendingDeviceRegistration,
    declineLabel: String,
    allowLabel: String,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().border(
            1.sdp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            RoundedCornerShape(12.sdp)
        ),
        shape  = RoundedCornerShape(12.sdp)
    ) {
        Column(Modifier.padding(16.sdp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeviceUnknown, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.sdp))
                Column {
                    Text(registration.deviceName.ifBlank { registration.status }, fontWeight = FontWeight.Medium)
                    Text(
                        registration.deviceType.ifBlank { registration.status },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.sdp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.sdp)) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(declineLabel) }
                Button(
                    onClick = onConfirm,
                    enabled = canConfirm,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(allowLabel)
                }
            }
        }
    }
}

@Composable
private fun QrDialog(
    payload: String,
    addDeviceLabel: String,
    copyCodeHint: String,
    copyCodeLabel: String,
    codeCopiedLabel: String,
    scanQrInsteadLabel: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    onNavigateToScan: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var codeCopied by remember { mutableStateOf(false) }

    val shortCode = payload

    LaunchedEffect(codeCopied) {
        if (codeCopied) {
            delay(2000)
            codeCopied = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.sdp)) {
            Column(
                modifier = Modifier
                    .padding(24.sdp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(addDeviceLabel, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.sdp))

                Image(
                    painter = rememberQrCodePainter(payload),
                    contentDescription = addDeviceLabel,
                    modifier = Modifier
                        .size(220.sdp)
                        .background(Color.White, RoundedCornerShape(8.sdp))
                        .border(2.sdp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.sdp))
                        .padding(8.sdp)
                )

                Spacer(Modifier.height(20.sdp))
                HorizontalDivider()
                Spacer(Modifier.height(12.sdp))

                Text(
                    copyCodeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.sdp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.sdp)
                        )
                        .padding(start = 12.sdp, end = 4.sdp, top = 10.sdp, bottom = 10.sdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shortCode,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 3
                    )
                    Spacer(Modifier.width(4.sdp))
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(shortCode))
                            codeCopied = true
                        },
                        modifier = Modifier.size(40.sdp)
                    ) {
                        Icon(
                            imageVector = if (codeCopied) Icons.Default.Check
                            else Icons.Default.ContentCopy,
                            contentDescription = copyCodeLabel,
                            tint = if (codeCopied) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.sdp)
                        )
                    }
                }

                AnimatedVisibility(visible = codeCopied) {
                    Text(
                        codeCopiedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.sdp)
                    )
                }

                Spacer(Modifier.height(16.sdp))
                TextButton(onClick = onNavigateToScan) {
                    Text(scanQrInsteadLabel)
                }
                TextButton(onClick = onDismiss) { Text(closeLabel) }
            }
        }
    }
}
