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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.VpnKey
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
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox
import com.example.memegram.utils.resolveTopBarTextColor
import kotlinx.coroutines.launch

private enum class CropTarget { AVATAR, COVER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: ProfileViewModel
) {
    val topBarTextColor = resolveTopBarTextColor(topBarColor)
    val s = LocalStrings.current
    val username      by viewModel.username.collectAsState()
    val bio           by viewModel.bio.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val error         by viewModel.error.collectAsState()
    val message       by viewModel.message.collectAsState()
    val avatarBytes   by viewModel.avatarBytes.collectAsState()
    val coverBytes    by viewModel.coverBytes.collectAsState()
    val myPublicKey   by viewModel.myPublicKey.collectAsState()

    var usernameInput by remember(username) { mutableStateOf(username) }
    var bioInput      by remember(bio)      { mutableStateOf(bio) }
    val scope = rememberCoroutineScope()
    var keyCopied by remember { mutableStateOf(false) }

    LaunchedEffect(keyCopied) {
        if (keyCopied) {
            kotlinx.coroutines.delay(2000)
            keyCopied = false
        }
    }

    var cropBytes by remember { mutableStateOf<ByteArray?>(null) }
    var cropTarget by remember { mutableStateOf<CropTarget?>(null) }

    val avatarPicker = com.example.memegram.picker.rememberImagePicker(multiple = false) { picked ->
        picked.firstOrNull()?.let { bytes ->
            cropBytes = bytes
            cropTarget = CropTarget.AVATAR
        }
    }

    val coverPicker = com.example.memegram.picker.rememberImagePicker(multiple = false) { picked ->
        picked.firstOrNull()?.let { bytes ->
            cropBytes = bytes
            cropTarget = CropTarget.COVER
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(s.error) },
            text  = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(s.ok) } }
        )
    }

    if (cropBytes != null && cropTarget != null) {
        val ratio = when (cropTarget!!) {
            CropTarget.AVATAR -> 1f
            CropTarget.COVER -> 2.5f
        }
        ImageCropScreen(
            imageBytes = cropBytes!!,
            aspectRatio = ratio,
            onCropped = { croppedBytes ->
                when (cropTarget!!) {
                    CropTarget.AVATAR -> viewModel.updateAvatar(croppedBytes)
                    CropTarget.COVER -> viewModel.updateCover(croppedBytes)
                }
                cropBytes = null
                cropTarget = null
            },
            onCancel = {
                cropBytes = null
                cropTarget = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                title = { Text(s.profileTitle) },
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.5f)
                    .clickable { coverPicker() },
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
                    modifier = Modifier.padding(10.sdp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.4f)
                ) {
                    Icon(
                        Icons.Default.CameraAlt, null,
                        modifier = Modifier.padding(7.sdp).size(16.sdp),
                        tint = Color.White
                    )
                }
            }

            Box(
                contentAlignment = Alignment.BottomEnd,
                modifier = Modifier.offset(y = (-38).sdp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.sdp)
                        .clip(CircleShape)
                        .border(3.sdp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable { avatarPicker() },
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
                                    fontSize = 28.ssp,
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
                        modifier = Modifier.padding(5.sdp).size(13.sdp),
                        tint = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-24).sdp)
                    .padding(horizontal = 16.sdp),
                verticalArrangement = Arrangement.spacedBy(12.sdp)
            ) {
                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    label = { Text(s.username) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.sdp),
                    trailingIcon = {
                        if (usernameInput != username) {
                            TextButton(
                                onClick = { viewModel.updateProfile(newUsername = usernameInput, newBio = bioInput) },
                                enabled = !isLoading
                            ) { Text(s.save) }
                        }
                    }
                )

                OutlinedTextField(
                    value = bioInput,
                    onValueChange = { bioInput = it },
                    label = { Text(s.aboutMe) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.sdp),
                    shape = RoundedCornerShape(12.sdp),
                    maxLines = 5,
                    trailingIcon = {
                        if (bioInput != bio) {
                            TextButton(
                                onClick = { viewModel.updateProfile(newUsername = usernameInput, newBio = bioInput) },
                                enabled = !isLoading
                            ) { Text(s.save) }
                        }
                    }
                )

                if (myPublicKey.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            copyTextToClipboard(myPublicKey)
                            keyCopied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.sdp)
                    ) {
                        Icon(
                            if (keyCopied) Icons.Default.ContentCopy else Icons.Default.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(16.sdp)
                        )
                        Spacer(Modifier.width(8.sdp))
                        Text(if (keyCopied) s.copied else s.copyMyPublicKey)
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(26.sdp)
                            .padding(top = 16.sdp),
                        strokeWidth = 2.5f.sdp
                    )
                }
            }
        }
    }
}
