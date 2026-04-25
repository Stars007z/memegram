package com.example.memegram

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonAdd
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    topBarColor: Color,
    userId: String,
    initialUsername: String,
    onBack: () -> Unit,
    onStartChat: (String) -> Unit,
    viewModel: UserProfileViewModel
) {
    val topBarTextColor = resolveTopBarTextColor(topBarColor)
    val s = LocalStrings.current
    val profile by viewModel.userProfile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.actionMessage.collectAsState()
    val avatarBytes by viewModel.avatarBytes.collectAsState()
    val coverBytes by viewModel.coverBytes.collectAsState()
    val isBlocked by viewModel.isBlocked.collectAsState()
    val isBlockedByPeer by viewModel.isBlockedByPeer.collectAsState()
    val isContact by viewModel.isContact.collectAsState()
    val isDeleted = profile?.isDeleted == true
    val snackbarHostState = remember { SnackbarHostState() }
    var showCannotMessageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(userId) {
        viewModel.loadUser(userId)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val displayUsername = if (isDeleted) s.deletedAccountTitle else (profile?.username ?: initialUsername)
    val displayBio = if (isDeleted) s.userDeletedAccountBanner else (profile?.bio ?: s.loading)

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
            TopAppBar(
                title = { Text(displayUsername, color = topBarTextColor) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isLoading && profile == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(2.5f),
                contentAlignment = Alignment.Center
            ) {
                if (!isDeleted && coverBytes != null) {
                    val bitmap = remember(coverBytes) {
                        runCatching { coverBytes!!.decodeToImageBitmap() }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap, contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {}
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {}
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.offset(y = (-40).sdp)
            ) {
                Box(
                    modifier = Modifier
                        .size(100.sdp)
                        .clip(CircleShape)
                        .border(3.sdp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isDeleted && avatarBytes != null) {
                        val bitmap = remember(avatarBytes) {
                            runCatching { avatarBytes!!.decodeToImageBitmap() }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap, contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.tertiary) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(if (isDeleted) "?" else displayUsername.take(1).uppercase(), color = Color.White, fontSize = 40.ssp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.tertiary) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(if (isDeleted) "?" else displayUsername.take(1).uppercase(), color = Color.White, fontSize = 40.ssp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Text(
                displayUsername,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(y = (-24).sdp)
            )

            Text(
                displayBio,
                color = Color.Gray,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.offset(y = (-16).sdp)
            )

            if (isDeleted || isBlocked || isBlockedByPeer) {
                val (badgeText, badgeColor) = when {
                    isDeleted -> s.userDeletedAccountBanner to MaterialTheme.colorScheme.onSurface
                    isBlockedByPeer -> s.youAreBlockedByUser to MaterialTheme.colorScheme.error
                    else -> s.youBlockedThisUser to MaterialTheme.colorScheme.error
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.sdp),
                    color = badgeColor.copy(alpha = 0.12f),
                    modifier = Modifier
                        .offset(y = (-8).sdp)
                        .padding(horizontal = 16.sdp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.sdp, vertical = 6.sdp)
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(16.sdp)
                        )
                        Spacer(Modifier.width(6.sdp))
                        Text(
                            badgeText,
                            color = badgeColor,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.sdp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.sdp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = {
                            if (isDeleted) {
                                showCannotMessageDialog = true
                            } else if (isBlockedByPeer) {
                                showCannotMessageDialog = true
                            } else {
                                profile?.userPublicKey?.let { onStartChat(it) }
                            }
                        },
                        modifier = Modifier.size(56.sdp),
                        enabled = !isDeleted && !isBlockedByPeer,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Message, null,
                            tint = if (isDeleted || isBlockedByPeer) LocalContentColor.current.copy(alpha = 0.5f) else LocalContentColor.current
                        )
                    }
                    Spacer(Modifier.height(8.sdp))
                    Text(s.sendMessage, style = MaterialTheme.typography.bodySmall)
                }

                if (!isDeleted && !isContact) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(
                            onClick = { viewModel.addToContacts() },
                            modifier = Modifier.size(56.sdp)
                        ) {
                            Icon(Icons.Default.PersonAdd, null)
                        }
                        Spacer(Modifier.height(8.sdp))
                        Text(s.addToContactsButton, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (!isDeleted) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = { if (isBlocked) viewModel.unblockUser() else viewModel.blockUser() },
                        modifier = Modifier.size(56.sdp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isBlocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Block, null,
                            tint = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(8.sdp))
                    Text(
                        if (isBlocked) s.unblockUser else s.blockUser,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (showCannotMessageDialog) {
        AlertDialog(
            onDismissRequest = { showCannotMessageDialog = false },
            title = { Text(s.cannotMessageTitle) },
            text = { Text(if (isDeleted) s.userDeletedAccountBanner else s.cannotMessageBlockedByPeer) },
            confirmButton = {
                TextButton(onClick = { showCannotMessageDialog = false }) {
                    Text(s.ok)
                }
            }
        )
    }
}
