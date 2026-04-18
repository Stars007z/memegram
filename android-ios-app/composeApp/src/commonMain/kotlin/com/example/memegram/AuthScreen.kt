package com.example.memegram

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp

@Composable
fun AuthScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel,
    onAddDevice: () -> Unit,
    languageViewModel: LanguageViewModel,
    themeViewModel: ThemeViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val s = LocalStrings.current
    val currentLang by languageViewModel.currentLang.collectAsState()
    val isDarkMode by themeViewModel.isDarkMode.collectAsState()
    var username by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var showLangMenu by remember { mutableStateOf(false) }

    data class LangOption(val code: String, val flag: String, val label: String)
    val languages = listOf(
        LangOption("en", "\uD83C\uDDFA\uD83C\uDDF8", "English"),
        LangOption("ru", "\uD83C\uDDF7\uD83C\uDDFA", "Русский"),
    )
    val currentFlag = languages.find { it.code == currentLang }?.flag ?: "\uD83C\uDDFA\uD83C\uDDF8"

    // Colors that adapt to dark mode
    val bgGradientStart = if (isDarkMode) Color(0xFF1B1B2F) else Color(0xFFF8F7FF)
    val bgGradientEnd = if (isDarkMode) Color(0xFF162447) else Color(0xFFEDE9FF)
    val cardBg = if (isDarkMode) Color(0xFF2A2A3E) else Color.White
    val accentColor = Color(0xFF6075F2)
    val textPrimary = if (isDarkMode) Color(0xFFE4E1E6) else Color(0xFF1A1A2E)
    val borderColor = if (isDarkMode) Color(0xFF3A3A50) else Color(0xFFE0E0E0)
    val errorBg = if (isDarkMode) Color(0xFF3D1F1F) else Color(0xFFFFEDED)
    val buttonBg = if (isDarkMode) Color(0xFF7B8BF5) else Color(0xFF6075F2)
    val buttonDisabled = if (isDarkMode) Color(0xFF4A4A6A) else Color(0xFFB0B8F8)
    val iconBubbleBg = if (isDarkMode) Color(0xFF2A2A3E) else Color.White

    LaunchedEffect(state) {
        if (state is AuthState.Success) onLoginSuccess()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(bgGradientStart, bgGradientEnd)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.sdp, end = 16.sdp),
            horizontalArrangement = Arrangement.spacedBy(10.sdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(44.sdp)
                    .clip(CircleShape)
                    .clickable { themeViewModel.toggleDarkMode() },
                shape = CircleShape,
                color = iconBubbleBg,
                shadowElevation = 4.sdp,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = isDarkMode,
                        transitionSpec = {
                            (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut())
                        }
                    ) { dark ->
                        Icon(
                            imageVector = if (dark) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                            tint = if (dark) Color(0xFFFFC107) else Color(0xFFFF9800),
                            modifier = Modifier.size(24.sdp)
                        )
                    }
                }
            }

            // Language selector
            Box {
                Surface(
                    modifier = Modifier
                        .size(44.sdp)
                        .clip(CircleShape)
                        .clickable { showLangMenu = true },
                    shape = CircleShape,
                    color = iconBubbleBg,
                    shadowElevation = 4.sdp,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(text = currentFlag, fontSize = 22.ssp)
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
                                    Text(text = lang.flag, fontSize = 20.ssp)
                                    Spacer(Modifier.width(12.sdp))
                                    Text(
                                        text = lang.label,
                                        fontWeight = if (lang.code == currentLang) FontWeight.Bold else FontWeight.Normal,
                                        color = if (lang.code == currentLang) accentColor else Color.Unspecified
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
        }

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 28.sdp)
                .padding(top = 76.sdp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(40.sdp))

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.Red)) { append("Meme") }
                    withStyle(SpanStyle(color = textPrimary)) { append("Gram") }
                },
                fontSize = 38.ssp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(36.sdp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.sdp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.sdp)
            ) {
                Column(modifier = Modifier.padding(20.sdp)) {
                    AuthTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = s.nickname,
                        icon = Icons.Default.Person,
                        isDarkMode = isDarkMode,
                        accentColor = accentColor,
                        borderColor = borderColor,
                    )
                    Spacer(modifier = Modifier.height(12.sdp))
                    AuthTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it },
                        label = s.inviteCode,
                        icon = Icons.Default.CardGiftcard,
                        placeholder = "XXXXXXXX",
                        isDarkMode = isDarkMode,
                        accentColor = accentColor,
                        borderColor = borderColor,
                    )
                }
            }

            AnimatedVisibility(visible = state is AuthState.Error) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.sdp),
                    shape = RoundedCornerShape(12.sdp),
                    colors = CardDefaults.cardColors(containerColor = errorBg)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.sdp, vertical = 10.sdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(18.sdp)
                        )
                        Spacer(modifier = Modifier.width(8.sdp))
                        Text(
                            text = (state as? AuthState.Error)?.message ?: "",
                            color = Color(0xFFD32F2F),
                            fontSize = 13.ssp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.sdp))

            Button(
                onClick = { viewModel.register(username, inviteCode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.sdp),
                shape = RoundedCornerShape(16.sdp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonBg,
                    disabledContainerColor = buttonDisabled
                ),
                enabled = state !is AuthState.Loading
            ) {
                if (state is AuthState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.sdp),
                        color = Color.White,
                        strokeWidth = 2.5.sdp
                    )
                } else {
                    Text(
                        text = s.register,
                        fontSize = 16.ssp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.sdp))

            TextButton(
                onClick = onAddDevice,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.sdp)
                )
                Spacer(Modifier.width(8.sdp))
                Text(s.loginFromOtherDevice)
            }
            Spacer(modifier = Modifier.height(24.sdp))
            Spacer(
                modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
            )
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
    isDarkMode: Boolean = false,
    accentColor: Color = Color(0xFF6075F2),
    borderColor: Color = Color(0xFFE0E0E0),
) {
    val placeholderColor = if (isDarkMode) Color(0xFF666680) else Color(0xFFCCCCCC)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 14.ssp) },
        placeholder = if (placeholder.isNotEmpty()) {{ Text(placeholder, color = placeholderColor) }} else null,
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.sdp))
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.sdp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = borderColor,
            focusedLabelColor = accentColor,
        )
    )
}