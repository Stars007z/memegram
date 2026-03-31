package com.example.memegram

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: StorageViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() < 0.5f) Color.White else Color.Black
    val cleanupStrategy by viewModel.cleanupStrategy.collectAsState()

    val selectedStrategy by viewModel.cleanupStrategy.collectAsState()
    val fifoLimit  by viewModel.fifoLimit.collectAsState()
    val ttlDays    by viewModel.ttlDays.collectAsState()
    val lruLimit   by viewModel.lruLimit.collectAsState()
    val lfuLimit   by viewModel.lfuLimit.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Данные и память") },
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
        ) {
            Text(
                "Автоматическая очистка кэша",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )

            val options = listOf(
                "TTL" to "Удалять старые по времени (1 месяц)",
                "LRU" to "Удалять наименее недавно использованные",
                "LFU" to "Удалять реже всего запрашиваемые",
                "FIFO" to "Удалять самые старые (FIFO)"
            )

            AnimatedVisibility(visible = true) {
                when (selectedStrategy) {
                    "FIFO" -> StrategyParamSlider(
                        label       = "Сообщений в чате (последних)",
                        description = "Старые сообщения сверх лимита будут удалены из этого чата",
                        value       = fifoLimit,
                        min         = 100L,
                        max         = 10_000L,
                        step        = 100L,
                        onChanged   = viewModel::updateFifoLimit
                    )
                    "TTL"  -> StrategyParamSlider(
                        label       = "Хранить сообщения (дней)",
                        description = "Сообщения старше указанного периода удаляются автоматически",
                        value       = ttlDays,
                        min         = 1L,
                        max         = 365L,
                        step        = 1L,
                        onChanged   = viewModel::updateTtlDays
                    )
                    "LRU"  -> StrategyParamSlider(
                        label       = "Глобальный лимит (сообщений)",
                        description = "Удаляются сообщения из чатов, которые давно не открывались",
                        value       = lruLimit,
                        min         = 500L,
                        max         = 50_000L,
                        step        = 500L,
                        onChanged   = viewModel::updateLruLimit
                    )
                    "LFU"  -> StrategyParamSlider(
                        label       = "Глобальный лимит (сообщений)",
                        description = "Удаляются сообщения из чатов, в которые реже всего заходят",
                        value       = lfuLimit,
                        min         = 500L,
                        max         = 50_000L,
                        step        = 500L,
                        onChanged   = viewModel::updateLfuLimit
                    )
                }
            }

            options.forEach { (key, title) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setCleanupStrategy(key) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = cleanupStrategy == key,
                        onClick = { viewModel.setCleanupStrategy(key) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = { viewModel.clearCache() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Очистить локальный кэш сейчас")
            }
        }
    }
}

@Composable
fun StrategyParamSlider(
    label: String,
    description: String,
    value: Long,
    min: Long,
    max: Long,
    step: Long,
    onChanged: (Long) -> Unit
) {
    var inputText by remember(value) { mutableStateOf(value.toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = inputText,
                onValueChange = { raw ->
                    inputText = raw
                    raw.toLongOrNull()?.coerceIn(min, max)?.let { onChanged(it) }
                },
                modifier = Modifier.width(96.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChanged(it.toLong()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / step - 1).toInt().coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$min", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center)
            Text("$max", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}