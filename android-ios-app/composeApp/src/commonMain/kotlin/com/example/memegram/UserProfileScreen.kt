package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    topBarColor: Color,
    userId: String,
    initialUsername: String,
    onBack: () -> Unit,
    onStartChat: (String) -> Unit, // Передаем public_key
    viewModel: UserProfileViewModel
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val profile by viewModel.userProfile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.actionMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(userId) {
        viewModel.loadUser(userId)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val displayUsername = profile?.username ?: initialUsername
    val displayBio = profile?.bio ?: "Загрузка..."

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayUsername, color = topBarTextColor) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = topBarTextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarColor)
            )
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

            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center
            ) {
                Text(displayUsername.take(1).uppercase(), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))
            Text(displayUsername, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(8.dp))
            Text(displayBio, color = Color.Gray, style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = { profile?.userPublicKey?.let { onStartChat(it) } },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Message, null)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Написать", style = MaterialTheme.typography.bodySmall)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = { viewModel.addToContacts() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, null)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("В контакты", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}