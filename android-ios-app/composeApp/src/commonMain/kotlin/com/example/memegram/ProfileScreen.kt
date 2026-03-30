package com.example.memegram

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: ProfileViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() < 0.5f) Color.White else Color.Black
    val username      by viewModel.username.collectAsState()
    val bio           by viewModel.bio.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val error         by viewModel.error.collectAsState()
    val avatarBytes   by viewModel.avatarBytes.collectAsState()
    val coverBytes    by viewModel.coverBytes.collectAsState()
    val myPublicKey   by viewModel.myPublicKey.collectAsState()

    var usernameInput by remember(username) { mutableStateOf(username) }
    var bioInput      by remember(bio)      { mutableStateOf(bio) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var keyCopied by remember { mutableStateOf(false) }

    val avatarPicker = rememberFilePickerLauncher(
        type = PickerType.Image, mode = PickerMode.Single
    ) { file -> file?.let { scope.launch { viewModel.updateAvatar(it.readBytes()) } } }

    val coverPicker = rememberFilePickerLauncher(
        type = PickerType.Image, mode = PickerMode.Single
    ) { file -> file?.let { scope.launch { viewModel.updateCover(it.readBytes()) } } }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("Ошибка") },
            text  = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Обложка ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clickable { coverPicker.launch() },
                contentAlignment = Alignment.BottomEnd
            ) {
                if (coverBytes != null) {
                    val bitmap = remember(coverBytes) {
                        runCatching { coverBytes!!.decodeToImageBitmap() }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap, contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {}
                }
                Surface(
                    modifier = Modifier.padding(10.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.4f)
                ) {
                    Icon(
                        Icons.Default.CameraAlt, null,
                        modifier = Modifier.padding(7.dp).size(16.dp),
                        tint = Color.White
                    )
                }
            }

            // ── Аватар ───────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.BottomEnd,
                modifier = Modifier.offset(y = (-38).dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable { avatarPicker.launch() },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBytes != null) {
                        val bitmap = remember(avatarBytes) {
                            runCatching { avatarBytes!!.decodeToImageBitmap() }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap, contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Surface(modifier = Modifier.fillMaxSize(), color = topBarColor) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    username.take(1).uppercase(),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = topBarTextColor
                                )
                            }
                        }
                    }
                }
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f)) {
                    Icon(
                        Icons.Default.CameraAlt, null,
                        modifier = Modifier.padding(5.dp).size(13.dp),
                        tint = Color.White
                    )
                }
            }

            // ── Поля профиля ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-24).dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    label = { Text("Имя пользователя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (usernameInput != username) {
                            TextButton(
                                onClick = { viewModel.updateUsername(usernameInput) },
                                enabled = !isLoading
                            ) { Text("Сохранить") }
                        }
                    }
                )

                OutlinedTextField(
                    value = bioInput,
                    onValueChange = { bioInput = it },
                    label = { Text("О себе") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 5,
                    trailingIcon = {
                        if (bioInput != bio) {
                            TextButton(
                                onClick = { viewModel.updateBio(bioInput) },
                                enabled = !isLoading
                            ) { Text("Сохранить") }
                        }
                    }
                )

                if (myPublicKey.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(myPublicKey))
                            keyCopied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (keyCopied) Icons.Default.ContentCopy else Icons.Default.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (keyCopied) "Скопировано!" else "Скопировать мой публичный ключ")
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(26.dp),
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }
    }
}