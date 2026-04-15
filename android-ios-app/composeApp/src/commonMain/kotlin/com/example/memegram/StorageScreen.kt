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
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ImageTopAppBarBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: StorageViewModel
) {
    val s = LocalStrings.current
    val topBarTextColor = if (topBarColor.luminance() < 0.5f) Color.White else Color.Black
    val cleanupStrategy by viewModel.cleanupStrategy.collectAsState()

    val selectedStrategy by viewModel.cleanupStrategy.collectAsState()
    val fifoLimit  by viewModel.fifoLimit.collectAsState()
    val ttlDays    by viewModel.ttlDays.collectAsState()
    val lruLimit   by viewModel.lruLimit.collectAsState()
    val lfuLimit   by viewModel.lfuLimit.collectAsState()

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                title = { Text(s.dataAndStorageTitle) },
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
        ) {
            Text(
                s.autoClearCache,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.sdp)
            )

            val options = listOf(
                "TTL"  to s.deleteOldByTime,
                "LRU"  to s.deleteLeastRecent,
                "LFU"  to s.deleteLeastRequested,
                "FIFO" to s.deleteOldestFifo
            )

            AnimatedVisibility(visible = true) {
                when (selectedStrategy) {
                    "FIFO" -> StrategyParamSlider(
                        label       = s.messagesInChat,
                        description = s.fifoDescription,
                        value       = fifoLimit,
                        min         = 100L,
                        max         = 10_000L,
                        step        = 100L,
                        onChanged   = viewModel::updateFifoLimit
                    )
                    "TTL"  -> StrategyParamSlider(
                        label       = s.storeMessagesDays,
                        description = s.ttlDescription,
                        value       = ttlDays,
                        min         = 1L,
                        max         = 365L,
                        step        = 1L,
                        onChanged   = viewModel::updateTtlDays
                    )
                    "LRU"  -> StrategyParamSlider(
                        label       = s.globalLimit,
                        description = s.lruDescription,
                        value       = lruLimit,
                        min         = 500L,
                        max         = 50_000L,
                        step        = 500L,
                        onChanged   = viewModel::updateLruLimit
                    )
                    "LFU"  -> StrategyParamSlider(
                        label       = s.globalLimit,
                        description = s.lfuDescription,
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
                        .padding(horizontal = 16.sdp, vertical = 12.sdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = cleanupStrategy == key,
                        onClick = { viewModel.setCleanupStrategy(key) }
                    )
                    Spacer(modifier = Modifier.width(12.sdp))
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(24.sdp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.sdp))

            OutlinedButton(
                onClick = { viewModel.clearCache() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.sdp),
                shape = RoundedCornerShape(12.sdp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.sdp))
                Spacer(Modifier.width(8.sdp))
                Text(s.clearLocalCache)
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
            .padding(horizontal = 16.sdp, vertical = 8.sdp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.sdp)
            )
            .padding(12.sdp)
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
                modifier = Modifier.width(96.sdp),
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
                modifier = Modifier.weight(1f).padding(horizontal = 8.sdp),
                textAlign = TextAlign.Center)
            Text("$max", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
