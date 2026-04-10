package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.localization.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackListScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: BlackListViewModel
) {
    val s = LocalStrings.current
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val blockedUsers by viewModel.blockedUsers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var pendingUnblockId by remember { mutableStateOf<String?>(null) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(s.error) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(s.ok) } }
        )
    }

    pendingUnblockId?.let { userId ->
        val name = blockedUsers.find { it.blockedUserId == userId }
            ?.profile?.username?.takeIf { it.isNotBlank() }
            ?: "@${userId.take(8)}"
        AlertDialog(
            onDismissRequest = { pendingUnblockId = null },
            title = { Text(s.unblockTitle) },
            text = { Text(s.unblockMessage(name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unblockUser(userId)
                    pendingUnblockId = null
                }) { Text(s.unblockAction) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnblockId = null }) { Text(s.cancel) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.blackListTitle) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                blockedUsers.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = s.blackListEmpty,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = s.blockedUsersHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(blockedUsers, key = { it.blockedUserId }) { entry ->
                            BlockedUserItem(
                                displayName = entry.profile?.username
                                    ?: (entry.blockedUserId.take(8) + "..."),
                                accentColor = topBarColor,
                                unblockLabel = s.unblockAction,
                                onUnblock = { pendingUnblockId = entry.blockedUserId }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedUserItem(
    displayName: String,
    accentColor: Color,
    unblockLabel: String,
    onUnblock: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayName.take(1).uppercase(),
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }

        TextButton(
            onClick = onUnblock,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(unblockLabel, fontSize = 13.sp)
        }
    }
}
