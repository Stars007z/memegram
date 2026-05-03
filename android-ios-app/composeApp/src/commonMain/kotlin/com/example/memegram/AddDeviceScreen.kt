package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    vm: AddDeviceViewModel = koinViewModel()
) {
    val s = LocalStrings.current
    val step  by vm.step.collectAsState()
    val error by vm.error.collectAsState()

    var manualInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(step) {
        if (step == AddDeviceStep.CONFIRMED) onSuccess()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.addDevice) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = s.back)
                    }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.sdp),
            contentAlignment = Alignment.Center
        ) {
            val contentMaxWidth = minOf(maxWidth, 420.sdp)
            val scannerSize = minOf(contentMaxWidth, maxHeight * 0.58f)

            when (step) {
                AddDeviceStep.SCANNING -> {
                    Column(
                        modifier = Modifier
                            .widthIn(max = contentMaxWidth)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            s.scanQrHint,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.sdp))

                        Box(
                            modifier = Modifier
                                .size(scannerSize)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.sdp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            QrScannerView(
                                modifier = Modifier.fillMaxSize(),
                                onScanned = vm::onQrScanned
                            )
                        }

                        Spacer(Modifier.height(16.sdp))
                        Text(
                            s.pasteCodeHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.sdp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualInput,
                                onValueChange = { manualInput = it },
                                label = { Text(s.codeOrQrLink) },
                                placeholder = { Text("regId/regCode") },
                                modifier = Modifier.weight(1f),
                                singleLine = false,
                                maxLines = 3
                            )
                            Spacer(Modifier.width(4.sdp))
                            IconButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let {
                                        manualInput = it.trim()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = s.paste,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(12.sdp))
                        Button(
                            onClick = { vm.onQrScanned(manualInput) },
                            enabled = manualInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(s.connect)
                        }
                    }
                }

                AddDeviceStep.SUBMITTING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.sdp)
                    ) {
                        CircularProgressIndicator()
                        Text(s.sendingDeviceData)
                    }
                }

                AddDeviceStep.WAITING_APPROVAL -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.sdp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            s.awaitingConfirmation,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            s.confirmOnMainDevice,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AddDeviceStep.CONFIRMED -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.sdp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.sdp)
                        )
                        Text(s.deviceAdded, style = MaterialTheme.typography.titleMedium)
                    }
                }

                AddDeviceStep.REJECTED, AddDeviceStep.ERROR -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.sdp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.sdp)
                        )
                        Text(
                            error ?: s.errorOccurred,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = vm::retryScanning) { Text(s.tryAgain) }
                    }
                }
            }
        }
    }
}

@Composable
expect fun QrScannerView(modifier: Modifier, onScanned: (String) -> Unit)
