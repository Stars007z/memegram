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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    onBack: () -> Unit,
    onNavigateToScanQr: () -> Unit,
    vm: LinkedDevicesViewModel = koinViewModel()
) {
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
        LaunchedEffect(it) { vm.clearSuccess() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Связанные устройства") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Добавить устройство ────────────────────────────────
            item {
                Button(
                    onClick = { vm.initAddDevice() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить устройство по QR")
                }
            }

            // ── Ожидающие подтверждения ───────────────────────────
            if (pending.isNotEmpty()) {
                item {
                    Text(
                        "Ожидают подтверждения",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(pending) { reg ->
                    PendingDeviceCard(
                        registration = reg,
                        onConfirm  = { vm.confirmAddition(reg.registrationId, true) },
                        onReject   = { vm.confirmAddition(reg.registrationId, false) }
                    )
                }
            }

            // ── Список устройств ──────────────────────────────────
            item {
                Text(
                    "Мои устройства",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(devices) { device ->
                DeviceCard(
                    device   = device,
                    onRevoke = { if (!device.isCurrentDevice) vm.revokeDevice(device.serverId) }
                )
            }
        }
    }

    // ── QR диалог ─────────────────────────────────────────────────
    qrPayload?.let { payload ->
        QrDialog(
            payload = payload,
            onDismiss = { vm.clearQr() },
            onNavigateToScan = {
                vm.clearQr()
                onNavigateToScanQr()
            }
        )
    }
}

@Composable
private fun DeviceCard(device: DeviceUiModel, onRevoke: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (device.isCurrentDevice)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (device.type == "primary") Icons.Default.PhoneAndroid
                else Icons.Default.Devices,
                contentDescription = null,
                tint = if (device.isCurrentDevice)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, fontWeight = FontWeight.SemiBold)
                    if (device.isCurrentDevice) {
                        Spacer(Modifier.width(6.dp))
                        Badge { Text("это устройство") }
                    }
                }
                Text(
                    device.type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!device.isActive) {
                    Text(
                        "Отозвано",
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
                        contentDescription = "Отозвать",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                if (showConfirm) {
                    AlertDialog(
                        onDismissRequest = { showConfirm = false },
                        title   = { Text("Отозвать устройство?") },
                        text    = { Text("'${device.name}' будет удалено из вашего аккаунта.") },
                        confirmButton = {
                            TextButton(onClick = { showConfirm = false; onRevoke() }) {
                                Text("Отозвать", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirm = false }) { Text("Отмена") }
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
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().border(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            RoundedCornerShape(12.dp)
        ),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeviceUnknown, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(registration.deviceName, fontWeight = FontWeight.Medium)
                    Text(
                        registration.deviceType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Отклонить") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text("Разрешить")
                }
            }
        }
    }
}

@Composable
private fun QrDialog(payload: String, onDismiss: () -> Unit, onNavigateToScan: () -> Unit) {
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
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Добавить устройство", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))

                Image(
                    painter = rememberQrCodePainter(payload),
                    contentDescription = "QR-код для добавления устройства",
                    modifier = Modifier
                        .size(220.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    "Или скопируйте код и вставьте его на новом устройстве",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shortCode,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 3
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(shortCode))
                            codeCopied = true
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (codeCopied) Icons.Default.Check
                            else Icons.Default.ContentCopy,
                            contentDescription = "Скопировать код",
                            tint = if (codeCopied) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = codeCopied) {
                    Text(
                        "✓ Скопировано",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onNavigateToScan) {
                    Text("Хочу отсканировать QR вместо этого")
                }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
}