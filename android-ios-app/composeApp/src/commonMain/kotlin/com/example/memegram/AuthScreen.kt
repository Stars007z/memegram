package com.example.memegram

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.localization.LocalStrings

@Composable
fun AuthScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel,
    onAddDevice: () -> Unit,
    languageViewModel: LanguageViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val s = LocalStrings.current
    val currentLang by languageViewModel.currentLang.collectAsState()
    var isLoginMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var showLangMenu by remember { mutableStateOf(false) }

    data class LangOption(val code: String, val flag: String, val label: String)
    val languages = listOf(
        LangOption("en", "\uD83C\uDDFA\uD83C\uDDF8", "English"),
        LangOption("ru", "\uD83C\uDDF7\uD83C\uDDFA", "Русский"),
    )
    val currentFlag = languages.find { it.code == currentLang }?.flag ?: "\uD83C\uDDFA\uD83C\uDDF8"

    LaunchedEffect(state) {
        if (state is AuthState.Success) onLoginSuccess()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF8F7FF), Color(0xFFEDE9FF))
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { showLangMenu = true },
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(text = currentFlag, fontSize = 22.sp)
                }
            }
            DropdownMenu(
                expanded = showLangMenu,
                onDismissRequest = { showLangMenu = false }
            ) {
                languages.forEach { lang ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = lang.flag, fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = lang.label,
                                    fontWeight = if (lang.code == currentLang) FontWeight.Bold else FontWeight.Normal,
                                    color = if (lang.code == currentLang) Color(0xFF6075F2) else Color.Unspecified
                                )
                            }
                        },
                        onClick = {
                            languageViewModel.setLanguage(lang.code)
                            showLangMenu = false
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.Red)) { append("Meme") }
                    withStyle(SpanStyle(color = Color(0xFF1A1A2E))) { append("Gram") }
                },
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(36.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    AnimatedVisibility(
                        visible = !isLoginMode,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            AuthTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = s.nickname,
                                icon = Icons.Default.Person,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AuthTextField(
                                value = inviteCode,
                                onValueChange = { inviteCode = it },
                                label = s.inviteCode,
                                icon = Icons.Default.CardGiftcard,
                                placeholder = "XXXXXXXX",
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isLoginMode,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Text(
                            text = s.autoLoginHint,
                            fontSize = 14.sp,
                            color = Color(0xFF888AA0),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state is AuthState.Error) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEDED))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = (state as? AuthState.Error)?.message ?: "",
                            color = Color(0xFFD32F2F),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (isLoginMode) viewModel.login()
                    else viewModel.register(username, inviteCode)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6075F2),
                    disabledContainerColor = Color(0xFFB0B8F8)
                ),
                enabled = state !is AuthState.Loading
            ) {
                if (state is AuthState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = if (isLoginMode) s.login else s.register,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = { isLoginMode = !isLoginMode }) {
                Text(
                    text = if (isLoginMode) s.noAccountRegister
                    else s.hasAccountLogin,
                    color = Color(0xFF6075F2),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
            TextButton(
                onClick = onAddDevice,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(s.loginFromOtherDevice)
            }
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 14.sp) },
        placeholder = if (placeholder.isNotEmpty()) {{ Text(placeholder, color = Color(0xFFCCCCCC)) }} else null,
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = Color(0xFF6075F2), modifier = Modifier.size(20.dp))
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF6075F2),
            unfocusedBorderColor = Color(0xFFE0E0E0),
            focusedLabelColor = Color(0xFF6075F2),
        )
    )
}