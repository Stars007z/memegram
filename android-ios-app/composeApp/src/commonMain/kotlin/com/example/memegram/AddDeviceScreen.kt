package com.example.memegram

import androidx.compose.foundation.background
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    vm: AddDeviceViewModel = koinViewModel()
) {
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
                title = { Text("Добавить устройство") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (step) {
                AddDeviceStep.SCANNING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Отсканируй QR-код\nс основного устройства",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            QrScannerView(
                                modifier = Modifier.fillMaxSize(),
                                onScanned = vm::onQrScanned
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Или вставьте код с другого устройства",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualInput,
                                onValueChange = { manualInput = it },
                                label = { Text("Код или QR-ссылка") },
                                placeholder = { Text("regId/regCode") },
                                modifier = Modifier.weight(1f),
                                singleLine = false,
                                maxLines = 3
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let {
                                        manualInput = it.trim()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Вставить",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.onQrScanned(manualInput) },
                            enabled = manualInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Подключить")
                        }
                    }
                }

                AddDeviceStep.SUBMITTING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Отправка данных устройства...")
                    }
                }

                AddDeviceStep.WAITING_APPROVAL -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Ожидаем подтверждения\nот основного устройства",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Зайди на основном телефоне в\nНастройки → Связанные устройства\nи нажми «Разрешить»",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AddDeviceStep.CONFIRMED -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Text("Устройство добавлено!", style = MaterialTheme.typography.titleMedium)
                    }
                }

                AddDeviceStep.REJECTED, AddDeviceStep.ERROR -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            error ?: "Произошла ошибка",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = vm::retryScanning) { Text("Попробовать снова") }
                    }
                }
            }
        }
    }
}

@Composable
expect fun QrScannerView(modifier: Modifier, onScanned: (String) -> Unit)