package com.example.memegram

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.example.memegram.localization.LocalStrings
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox
import com.example.memegram.utils.LocalScreenWidthDp
import com.example.memegram.utils.LocalScreenHeightDp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars

// ── HSV utilities ────────────────────────────────────────────────────
private data class HsvColor(val hue: Float, val saturation: Float, val value: Float) {
    fun toColor(): Color {
        val c = value * saturation
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = value - c
        val (r, g, b) = when {
            hue < 60f  -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else       -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m, 1f)
    }

    companion object {
        fun fromColor(color: Color): HsvColor {
            val r = color.red; val g = color.green; val b = color.blue
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
            val hue = when {
                delta == 0f -> 0f
                max == r -> 60f * (((g - b) / delta) % 6f)
                max == g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }
            val saturation = if (max == 0f) 0f else delta / max
            return HsvColor(hue, saturation, max)
        }
    }
}

private fun Color.toHexString(): String {
    val argb = this.toArgb()
    val rgb = argb and 0xFFFFFF
    val hex = rgb.toString(16).uppercase()
    return hex.padStart(6, '0')
}

private fun hexToColor(hex: String): Color? {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6) return null
    return try { Color(clean.toLong(16).toInt() or (0xFF shl 24)) } catch (_: Exception) { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    onTopBarColorChanged: () -> Unit,
    viewModel: AppearanceViewModel,
    themeViewModel: ThemeViewModel
) {
    val s = LocalStrings.current
    val chatBgColor by viewModel.chatBgColor.collectAsState()
    val myBubbleColor by viewModel.myBubbleColor.collectAsState()
    val theirBubbleColor by viewModel.theirBubbleColor.collectAsState()
    val isDarkMode by themeViewModel.isDarkMode.collectAsState()

    val chatBgImage by viewModel.chatBgImage.collectAsState()
    val topBarImage by viewModel.topBarImage.collectAsState()
    val myBubbleImage by viewModel.myBubbleImage.collectAsState()
    val theirBubbleImage by viewModel.theirBubbleImage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showColorPickerForKey by remember { mutableStateOf<String?>(null) }
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val scope = rememberCoroutineScope()

    var cropBytes by remember { mutableStateOf<ByteArray?>(null) }
    var cropKey by remember { mutableStateOf<String?>(null) }

    val chatBgPicker = rememberFilePickerLauncher(PickerType.Image, PickerMode.Single) { f ->
        f?.let { scope.launch { cropBytes = it.readBytes(); cropKey = "chatbg" } }
    }
    val topBarPicker = rememberFilePickerLauncher(PickerType.Image, PickerMode.Single) { f ->
        f?.let { scope.launch { cropBytes = it.readBytes(); cropKey = "topbar" } }
    }
    val myBubblePicker = rememberFilePickerLauncher(PickerType.Image, PickerMode.Single) { f ->
        f?.let { scope.launch { cropBytes = it.readBytes(); cropKey = "mybubble" } }
    }
    val theirBubblePicker = rememberFilePickerLauncher(PickerType.Image, PickerMode.Single) { f ->
        f?.let { scope.launch { cropBytes = it.readBytes(); cropKey = "theirbubble" } }
    }

    val screenWidthDp = LocalScreenWidthDp.current
    val screenHeightDp = LocalScreenHeightDp.current

    val density = LocalDensity.current
    val statusBarDp = with(density) { WindowInsets.statusBars.getTop(density).toDp().value }
    val topBarTotalHeight = 64f + statusBarDp

    if (cropBytes != null && cropKey != null) {
        val ratio = when (cropKey!!) {
            "topbar" -> screenWidthDp / topBarTotalHeight
            "chatbg" -> screenWidthDp / (screenHeightDp - 144f)
            else -> 0f
        }
        ImageCropScreen(
            imageBytes = cropBytes!!,
            aspectRatio = ratio,
            onCropped = { croppedBytes ->
                viewModel.updateImage(cropKey!!, croppedBytes)
                if (cropKey == "topbar") onTopBarColorChanged()
                cropBytes = null; cropKey = null
            },
            onCancel = { cropBytes = null; cropKey = null }
        )
        return
    }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                title = { Text(s.appearanceTitle) },
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
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Theme toggle ─────────────────────────────────────
                Text(
                    text = s.themeSection,
                    modifier = Modifier.padding(start = 16.sdp, top = 16.sdp, bottom = 8.sdp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.sdp)
                        .clip(RoundedCornerShape(16.sdp))
                        .clickable { themeViewModel.toggleDarkMode() },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.sdp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.sdp, vertical = 16.sdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedContent(
                            targetState = isDarkMode,
                            transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) }
                        ) { dark ->
                            Icon(
                                imageVector = if (dark) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                                tint = if (dark) Color(0xFFFFC107) else Color(0xFFFF9800),
                                modifier = Modifier.size(26.sdp)
                            )
                        }
                        Spacer(Modifier.width(16.sdp))
                        Text(s.darkTheme, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { themeViewModel.setDarkMode(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF6075F2))
                        )
                    }
                }

                Spacer(Modifier.height(16.sdp))

                // ── Preview ──────────────────────────────────────────
                Text(
                    text = s.preview,
                    modifier = Modifier.padding(start = 16.sdp, bottom = 8.sdp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.sdp)
                        .padding(horizontal = 16.sdp)
                        .clip(RoundedCornerShape(12.sdp))
                        .background(chatBgColor)
                        .border(1.sdp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.sdp))
                ) {
                    if (chatBgImage != null) {
                        val bmp = remember(chatBgImage) { chatBgImage?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
                        bmp?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        val previewTopBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(screenWidthDp / topBarTotalHeight)) {
                            val topBarBmp = remember(topBarImage) { topBarImage?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
                            if (topBarBmp != null) {
                                Image(bitmap = topBarBmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Surface(color = topBarColor, modifier = Modifier.fillMaxSize()) {}
                            }
                            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 16.sdp)) {
                                Text(s.chatPreview, color = previewTopBarTextColor, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // ── Bubble previews ──────────────────────────
                        val theirBubbleBmp = remember(theirBubbleImage) { theirBubbleImage?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
                        val myBubbleBmp = remember(myBubbleImage) { myBubbleImage?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }

                        val theirTextColor = if (theirBubbleBmp != null) Color.White
                            else if (theirBubbleColor.luminance() > 0.5f) Color.Black else Color.White
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.sdp), contentAlignment = Alignment.CenterStart) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .clip(RoundedCornerShape(16.sdp, 16.sdp, 16.sdp, 4.sdp))
                                    .background(theirBubbleColor)
                            ) {
                                if (theirBubbleBmp != null) {
                                    Image(bitmap = theirBubbleBmp, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                                }
                                Text(s.previewMessage1, color = theirTextColor, modifier = Modifier.padding(horizontal = 14.sdp, vertical = 10.sdp))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.sdp))

                        val myTextColor = if (myBubbleBmp != null) Color.White
                            else if (myBubbleColor.luminance() > 0.5f) Color.Black else Color.White
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.sdp), contentAlignment = Alignment.CenterEnd) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .clip(RoundedCornerShape(16.sdp, 16.sdp, 4.sdp, 16.sdp))
                                    .background(myBubbleColor)
                            ) {
                                if (myBubbleBmp != null) {
                                    Image(bitmap = myBubbleBmp, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                                }
                                Text(s.previewMessage2, color = myTextColor, modifier = Modifier.padding(horizontal = 14.sdp, vertical = 10.sdp))
                            }
                        }

                        Spacer(modifier = Modifier.height(16.sdp))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.sdp))

                // ── Color/Photo settings ─────────────────────────────
                AppearanceSettingItem(
                    title = s.topBarColor,
                    currentColor = topBarColor,
                    hasImage = topBarImage != null,
                    onColorClick = { showColorPickerForKey = "topbar" },
                    onPhotoClick = { topBarPicker.launch() }
                )
                AppearanceSettingItem(
                    title = s.chatBgColor,
                    currentColor = chatBgColor,
                    hasImage = chatBgImage != null,
                    onColorClick = { showColorPickerForKey = "chatbg" },
                    onPhotoClick = { chatBgPicker.launch() }
                )
                AppearanceSettingItem(
                    title = s.myMessageColor,
                    currentColor = myBubbleColor,
                    hasImage = myBubbleImage != null,
                    onColorClick = { showColorPickerForKey = "mybubble" },
                    onPhotoClick = { myBubblePicker.launch() }
                )
                AppearanceSettingItem(
                    title = s.otherMessageColor,
                    currentColor = theirBubbleColor,
                    hasImage = theirBubbleImage != null,
                    onColorClick = { showColorPickerForKey = "theirbubble" },
                    onPhotoClick = { theirBubblePicker.launch() }
                )

                Spacer(Modifier.height(24.sdp))

                if (showColorPickerForKey != null) {
                    val currentColor = when (showColorPickerForKey) {
                        "topbar" -> topBarColor
                        "chatbg" -> chatBgColor
                        "mybubble" -> myBubbleColor
                        "theirbubble" -> theirBubbleColor
                        else -> Color.White
                    }
                    ColorPickerDialog(
                        title = s.chooseColor,
                        cancelLabel = s.cancel,
                        selectLabel = s.selectColor,
                        presetsLabel = s.colorPickerPresets,
                        customLabel = s.colorPickerCustom,
                        hexLabel = s.colorPickerHex,
                        initialColor = currentColor,
                        onColorSelected = { color ->
                            viewModel.updateColor(showColorPickerForKey!!, color)
                            if (showColorPickerForKey == "topbar") onTopBarColorChanged()
                            showColorPickerForKey = null
                        },
                        onDismiss = { showColorPickerForKey = null }
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Color.White) }
            }
        }
    }
}

@Composable
private fun AppearanceSettingItem(
    title: String,
    currentColor: Color,
    hasImage: Boolean,
    onColorClick: () -> Unit,
    onPhotoClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onColorClick)
            .padding(horizontal = 16.sdp, vertical = 12.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Surface(
            modifier = Modifier
                .size(36.sdp)
                .clip(CircleShape)
                .clickable(onClick = onPhotoClick),
            shape = CircleShape,
            color = if (hasImage) Color(0xFF6075F2) else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.sdp)
                )
            }
        }
        Spacer(Modifier.width(10.sdp))
        Box(
            modifier = Modifier
                .size(36.sdp)
                .clip(CircleShape)
                .background(currentColor)
                .border(1.sdp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

@Composable
fun ColorPickerDialog(
    title: String,
    cancelLabel: String,
    selectLabel: String,
    presetsLabel: String,
    customLabel: String,
    hexLabel: String,
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val presetColors = listOf(
        Color(0xFF6075F2), Color(0xFFF5F5F5), Color(0xFFD1C4E9), Color(0xFFFFFFFF),
        Color(0xFFFF5252), Color(0xFFFF4081), Color(0xFFE040FB), Color(0xFF7C4DFF),
        Color(0xFF536DFE), Color(0xFF448AFF), Color(0xFF40C4FF), Color(0xFF18FFFF),
        Color(0xFF64FFDA), Color(0xFF69F0AE), Color(0xFFB2FF59), Color(0xFFEEFF41),
        Color(0xFF212121), Color(0xFFFFD740), Color(0xFFFFAB40), Color(0xFFFF6E40),
        Color(0xFF795548), Color(0xFF455A64), Color(0xFFE0E0E0), Color(0xFF9E9E9E),
    )

    var hsv by remember { mutableStateOf(HsvColor.fromColor(initialColor)) }
    var hexInput by remember { mutableStateOf(initialColor.toHexString()) }
    var showCustomPicker by remember { mutableStateOf(false) }
    val selectedColor = hsv.toColor()

    LaunchedEffect(hsv) { hexInput = hsv.toColor().toHexString() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(32.sdp).clip(CircleShape)
                        .background(selectedColor).border(2.sdp, MaterialTheme.colorScheme.outline, CircleShape)
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.sdp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TabButton(presetsLabel, !showCustomPicker) { showCustomPicker = false }
                    TabButton(customLabel, showCustomPicker) { showCustomPicker = true }
                }
                Spacer(Modifier.height(16.sdp))
                if (!showCustomPicker) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxWidth().height(220.sdp)
                    ) {
                        items(presetColors) { color ->
                            val isSelected = color.toArgb() == selectedColor.toArgb()
                            Box(
                                modifier = Modifier.padding(6.sdp).size(36.sdp).clip(CircleShape)
                                    .background(color)
                                    .then(if (isSelected) Modifier.border(3.sdp, Color(0xFF6075F2), CircleShape)
                                    else Modifier.border(1.sdp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                                    .clickable { hsv = HsvColor.fromColor(color) }
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SaturationValuePanel(
                            hue = hsv.hue, saturation = hsv.saturation, value = hsv.value,
                            onSatValChanged = { s, v -> hsv = hsv.copy(saturation = s, value = v) },
                            modifier = Modifier.fillMaxWidth().height(180.sdp).clip(RoundedCornerShape(8.sdp))
                        )
                        Spacer(Modifier.height(12.sdp))
                        HueBar(
                            hue = hsv.hue, onHueChanged = { h -> hsv = hsv.copy(hue = h) },
                            modifier = Modifier.fillMaxWidth().height(32.sdp).clip(RoundedCornerShape(8.sdp))
                        )
                        Spacer(Modifier.height(16.sdp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("#", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.sdp))
                            OutlinedTextField(
                                value = hexInput,
                                onValueChange = { newHex ->
                                    if (newHex.length <= 6) {
                                        hexInput = newHex.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
                                        if (hexInput.length == 6) hexToColor(hexInput)?.let { c -> hsv = HsvColor.fromColor(c) }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(52.sdp),
                                singleLine = true, shape = RoundedCornerShape(8.sdp),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                label = { Text(hexLabel, fontSize = 11.ssp) }
                            )
                            Spacer(Modifier.width(12.sdp))
                            Box(
                                modifier = Modifier.size(44.sdp).clip(RoundedCornerShape(8.sdp))
                                    .background(selectedColor).border(2.sdp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.sdp))
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onColorSelected(selectedColor) }) { Text(selectLabel, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } }
    )
}

@Composable
private fun RowScope.TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
    val textColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    Box(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.sdp)).background(bgColor)
            .clickable(onClick = onClick).padding(vertical = 10.sdp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = textColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.ssp) }
}

@Composable
private fun SaturationValuePanel(hue: Float, saturation: Float, value: Float, onSatValChanged: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val hueColor = HsvColor(hue, 1f, 1f).toColor()
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { off -> onSatValChanged((off.x / size.width).coerceIn(0f, 1f), 1f - (off.y / size.height).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); onSatValChanged((ch.position.x / size.width).coerceIn(0f, 1f), 1f - (ch.position.y / size.height).coerceIn(0f, 1f)) } }
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)), size = size)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), size = size)
            val cx = saturation * size.width; val cy = (1f - value) * size.height
            drawCircle(Color.White, 10f, Offset(cx, cy), style = Stroke(3f))
            drawCircle(Color.Black, 12f, Offset(cx, cy), style = Stroke(1.5f))
        }
    }
}

@Composable
private fun HueBar(hue: Float, onHueChanged: (Float) -> Unit, modifier: Modifier = Modifier) {
    val rainbow = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { off -> onHueChanged((off.x / size.width * 360f).coerceIn(0f, 360f)) } }
                .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); onHueChanged((ch.position.x / size.width * 360f).coerceIn(0f, 360f)) } }
        ) {
            drawRect(Brush.horizontalGradient(rainbow), size = size)
            val px = hue / 360f * size.width
            drawCircle(Color.White, size.height / 2f - 2f, Offset(px, size.height / 2f), style = Stroke(3f))
            drawCircle(Color.Black, size.height / 2f, Offset(px, size.height / 2f), style = Stroke(1.5f))
        }
    }
}
