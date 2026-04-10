package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.memegram.localization.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    onTopBarColorChanged: () -> Unit,
    viewModel: AppearanceViewModel
) {
    val s = LocalStrings.current
    val chatBgColor by viewModel.chatBgColor.collectAsState()
    val myBubbleColor by viewModel.myBubbleColor.collectAsState()
    val theirBubbleColor by viewModel.theirBubbleColor.collectAsState()

    var showColorPickerForKey by remember { mutableStateOf<String?>(null) }
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.appearanceTitle) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = s.preview,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(chatBgColor)
                    .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val previewTopBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
                    Surface(
                        color = topBarColor,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(s.chatPreview, color = previewTopBarTextColor, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val theirTextColor = if (theirBubbleColor.luminance() > 0.5f) Color.Black else Color.White
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)).background(theirBubbleColor).padding(12.dp)) {
                            Text(s.previewMessage1, color = theirTextColor)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val myTextColor = if (myBubbleColor.luminance() > 0.5f) Color.Black else Color.White
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(myBubbleColor).padding(12.dp)) {
                            Text(s.previewMessage2, color = myTextColor)
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingItem(s.topBarColor, topBarColor) { showColorPickerForKey = "topbar" }
            SettingItem(s.chatBgColor, chatBgColor) { showColorPickerForKey = "chatbg" }
            SettingItem(s.myMessageColor, myBubbleColor) { showColorPickerForKey = "mybubble" }
            SettingItem(s.otherMessageColor, theirBubbleColor) { showColorPickerForKey = "theirbubble" }

            if (showColorPickerForKey != null) {
                ColorPickerDialog(
                    title = s.chooseColor,
                    cancelLabel = s.cancel,
                    onColorSelected = { color ->
                        viewModel.updateColor(showColorPickerForKey!!, color)
                        if (showColorPickerForKey == "topbar") {
                            onTopBarColorChanged()
                        }
                        showColorPickerForKey = null
                    },
                    onDismiss = { showColorPickerForKey = null }
                )
            }
        }
    }
}

@Composable
fun SettingItem(title: String, currentColor: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(currentColor)
                .border(1.dp, Color.Gray, CircleShape)
        )
    }
}

@Composable
fun ColorPickerDialog(
    title: String,
    cancelLabel: String,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = listOf(
        Color(0xFF6075F2), Color(0xFFF5F5F5), Color(0xFFD1C4E9), Color(0xFFFFFFFF),
        Color(0xFFFF5252), Color(0xFFFF4081), Color(0xFFE040FB), Color(0xFF7C4DFF),
        Color(0xFF536DFE), Color(0xFF448AFF), Color(0xFF40C4FF), Color(0xFF18FFFF),
        Color(0xFF64FFDA), Color(0xFF69F0AE), Color(0xFFB2FF59), Color(0xFFEEFF41),
        Color(0xFF212121), Color(0xFFFFD740), Color(0xFFFFAB40), Color(0xFFFF6E40)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(250.dp)) {
                items(colors) { color ->
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, Color.LightGray, CircleShape)
                            .clickable { onColorSelected(color) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        }
    )
}
