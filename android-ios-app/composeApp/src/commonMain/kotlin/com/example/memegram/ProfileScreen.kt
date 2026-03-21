package com.example.memegram

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: ProfileViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val username by viewModel.username.collectAsState()
    val bio by viewModel.bio.collectAsState()
    val avatarBytes by viewModel.avatarBytes.collectAsState()
    val scope = rememberCoroutineScope()
    var editingName by remember { mutableStateOf(false) }
    var editingBio by remember { mutableStateOf(false) }
    var nameInput by remember(username) { mutableStateOf(username) }
    var bioInput by remember(bio) { mutableStateOf(bio) }

    val avatarPickerLauncher = rememberFilePickerLauncher(
        type = PickerType.Image,
        mode = PickerMode.Single
    ) { file ->
        file?.let {
            scope.launch {
                viewModel.updateAvatar(it.readBytes())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            val coverBytes by viewModel.coverBytes.collectAsState()

            val coverPickerLauncher = rememberFilePickerLauncher(
                type = PickerType.Image,
                mode = PickerMode.Single
            ) { file ->
                file?.let { scope.launch { viewModel.updateCover(it.readBytes()) } }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(topBarColor.copy(alpha = 0.4f))
                        .clickable { coverPickerLauncher.launch() },
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBytes != null) {
                        val coverBitmap = remember(coverBytes) { coverBytes!!.decodeToImageBitmap() }
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(32.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(topBarColor)
                            .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                            .clickable { avatarPickerLauncher.launch() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBytes != null) {
                            val imageBitmap = remember(avatarBytes) { avatarBytes!!.decodeToImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = username.take(1).uppercase(),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.background, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Spacer(Modifier.height(24.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

                    Text("Никнейм", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    if (editingName) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = {
                                        if (nameInput.isNotBlank())
                                            viewModel.updateUsername(nameInput.trim())
                                        editingName = false
                                    }) {
                                        Icon(Icons.Default.Check, null, tint = topBarColor)
                                    }
                                    IconButton(onClick = { nameInput = username; editingName = false }) {
                                        Icon(Icons.Default.Close, null)
                                    }
                                }
                            }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingName = true }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(username.ifBlank { "Не задан" }, fontSize = 15.sp)
                            Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("О себе", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    if (editingBio) {
                        OutlinedTextField(
                            value = bioInput,
                            onValueChange = { bioInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = {
                                        viewModel.updateBio(bioInput.trim())
                                        editingBio = false
                                    }) {
                                        Icon(Icons.Default.Check, null, tint = topBarColor)
                                    }
                                    IconButton(onClick = { bioInput = bio; editingBio = false }) {
                                        Icon(Icons.Default.Close, null)
                                    }
                                }
                            }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingBio = true }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bio.ifBlank { "Не задано" },
                                fontSize = 15.sp,
                                color = if (bio.isBlank()) Color.Gray else MaterialTheme.colorScheme.onSurface
                            )
                            Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}