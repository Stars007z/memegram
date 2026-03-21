package com.example.memegram

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class BlockedUser(val id: Int, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackListScreen(
    topBarColor: Color,
    onBack: () -> Unit
) {
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White

    var blockedUsers by remember {
        mutableStateOf(
            listOf(
                BlockedUser(1, "123"),
                BlockedUser(2, "Bot"),
                BlockedUser(3, "Ex")
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чёрный список") },
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
        if (blockedUsers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Список заблокированных пуст",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                itemsIndexed(blockedUsers) { index, user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = user.name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        TextButton(
                            onClick = {
                                blockedUsers = blockedUsers.toMutableList()
                                    .apply { removeAt(index) }
                            }
                        ) {
                            Text("Разблокировать", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (index < blockedUsers.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}