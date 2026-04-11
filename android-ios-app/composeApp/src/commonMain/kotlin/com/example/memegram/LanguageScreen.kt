package com.example.memegram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memegram.localization.LocalStrings

data class Language(
    val code: String,
    val nameNative: String,
    val nameEnglish: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    viewModel: LanguageViewModel
) {
    val s = LocalStrings.current
    val topBarTextColor = if (topBarColor.luminance() > 0.5f) Color.Black else Color.White
    val currentLang by viewModel.currentLang.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var aiTranslation by remember { mutableStateOf(false) }

    val allLanguages = remember {
        listOf(
            Language("en", "English", "English"),
            Language("ru", "Русский", "Russian"),
            Language("de", "Deutsch", "German"),
            Language("fr", "Français", "French"),
            Language("es", "Español", "Spanish"),
            Language("zh", "中文", "Chinese"),
            Language("ar", "العربية", "Arabic"),
            Language("pt", "Português", "Portuguese"),
            Language("it", "Italiano", "Italian"),
            Language("ja", "日本語", "Japanese"),
            Language("ko", "한국어", "Korean"),
            Language("tr", "Türkçe", "Turkish"),
        )
    }

    val filteredLanguages = remember(searchQuery, allLanguages) {
        if (searchQuery.isBlank()) allLanguages
        else allLanguages.filter {
            it.nameNative.contains(searchQuery, ignoreCase = true) ||
                    it.nameEnglish.contains(searchQuery, ignoreCase = true) ||
                    it.code.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.languageTitle) },
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
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(s.searchLanguage) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = s.aiTranslation,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                        Text(
                            text = s.comingSoon,
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = aiTranslation,
                        onCheckedChange = { aiTranslation = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = topBarColor,
                            checkedTrackColor = topBarColor.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredLanguages, key = { it.code }) { language ->
                    val isSelected = currentLang == language.code
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setLanguage(language.code)
                            },
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = if (isSelected) 4.dp else 1.dp,
                        color = if (isSelected)
                            topBarColor.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = language.nameNative,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                    color = if (isSelected) topBarColor else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = language.nameEnglish,
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = topBarColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
