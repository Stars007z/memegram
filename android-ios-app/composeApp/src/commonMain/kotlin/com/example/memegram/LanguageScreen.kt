package com.example.memegram

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.example.memegram.localization.LocalStrings
import com.example.memegram.translation.langNativeName
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import com.example.memegram.utils.ImageTopAppBarBox
import com.example.memegram.utils.resolveTopBarTextColor

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
    val topBarTextColor = resolveTopBarTextColor(topBarColor)
    val currentLang by viewModel.currentLang.collectAsState()
    val autoTranslateEnabled by viewModel.autoTranslateEnabled.collectAsState()
    val targetLanguage by viewModel.targetLanguage.collectAsState()
    val blacklistedLanguages by viewModel.blacklistedLanguages.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val modelSize by viewModel.modelSize.collectAsState()
    val whisperState by viewModel.whisperState.collectAsState()
    val whisperSize by viewModel.whisperSize.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    var showTargetLangDialog by remember { mutableStateOf(false) }
    var showBlacklistDialog by remember { mutableStateOf(false) }

    val allLanguages = remember {
        listOf(
            Language("en", "English", "English"),
            Language("ru", "Русский", "Russian"),
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

    if (showTargetLangDialog) {
        val effectiveTarget = targetLanguage.ifBlank { currentLang }
        AlertDialog(
            onDismissRequest = { showTargetLangDialog = false },
            title = { Text(s.targetLanguage) },
            text = {
                LazyColumn {
                    items(allLanguages, key = { it.code }) { language ->
                        val isSelected = effectiveTarget == language.code
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setTargetLanguage(language.code)
                                    showTargetLangDialog = false
                                }
                                .padding(vertical = 12.sdp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = language.nameNative,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) topBarColor else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = language.nameEnglish,
                                    fontSize = 12.ssp,
                                    color = Color.Gray
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = topBarColor)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTargetLangDialog = false }) {
                    Text(s.close)
                }
            }
        )
    }

    if (showBlacklistDialog) {
        val effectiveTarget = targetLanguage.ifBlank { currentLang }
        AlertDialog(
            onDismissRequest = { showBlacklistDialog = false },
            title = { Text(s.dontTranslate) },
            text = {
                LazyColumn {
                    items(allLanguages, key = { it.code }) { language ->
                        val isBlacklisted = language.code in blacklistedLanguages
                        val isAppLang = language.code == currentLang
                        val isTargetLang = language.code == effectiveTarget
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleBlacklistLanguage(language.code) }
                                .padding(vertical = 8.sdp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(language.nameNative)
                                Row {
                                    Text(
                                        text = language.nameEnglish,
                                        fontSize = 12.ssp,
                                        color = Color.Gray
                                    )
                                    if (isAppLang || isTargetLang) {
                                        val hint = when {
                                            isAppLang && isTargetLang -> " (${s.appLanguageHint}, ${s.targetLanguageHint})"
                                            isAppLang -> " (${s.appLanguageHint})"
                                            else -> " (${s.targetLanguageHint})"
                                        }
                                        Text(
                                            text = hint,
                                            fontSize = 12.ssp,
                                            color = topBarColor.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                            Checkbox(
                                checked = isBlacklisted,
                                onCheckedChange = { viewModel.toggleBlacklistLanguage(language.code) },
                                colors = CheckboxDefaults.colors(checkedColor = topBarColor)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlacklistDialog = false }) {
                    Text(s.ok)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
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
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(s.searchLanguage) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.sdp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.sdp, vertical = 12.sdp)
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.sdp)
                    .padding(bottom = 8.sdp),
                shape = RoundedCornerShape(12.sdp),
                tonalElevation = 2.sdp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.sdp, vertical = 14.sdp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = s.aiTranslation,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.ssp
                        )
                        Switch(
                            checked = autoTranslateEnabled,
                            onCheckedChange = { viewModel.setAutoTranslateEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = topBarColor,
                                checkedTrackColor = topBarColor.copy(alpha = 0.4f)
                            )
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.sdp)
                    )

                    ModelDownloadRow(
                        state = modelState,
                        modelSize = modelSize,
                        accent = topBarColor,
                        strings = s,
                        onDownload = viewModel::downloadModel,
                        onCancel = viewModel::cancelDownload,
                        onDelete = viewModel::deleteModel,
                    )

                    AnimatedVisibility(visible = autoTranslateEnabled) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.sdp)
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showTargetLangDialog = true }
                                    .padding(horizontal = 16.sdp, vertical = 14.sdp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = s.targetLanguage,
                                    fontSize = 14.ssp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val displayLang = targetLanguage.ifBlank { currentLang }
                                    Text(
                                        text = langNativeName(displayLang),
                                        color = Color.Gray,
                                        fontSize = 14.ssp
                                    )
                                    Icon(
                                        Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(20.sdp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showBlacklistDialog = true }
                                    .padding(horizontal = 16.sdp, vertical = 14.sdp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = s.dontTranslate,
                                    fontSize = 14.ssp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (blacklistedLanguages.isNotEmpty()) {
                                        Text(
                                            text = blacklistedLanguages
                                                .take(3)
                                                .joinToString(", ") { langNativeName(it) },
                                            color = Color.Gray,
                                            fontSize = 12.ssp,
                                            maxLines = 1
                                        )
                                    }
                                    Icon(
                                        Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(20.sdp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.sdp)
                    .padding(top = 8.sdp, bottom = 8.sdp),
                shape = RoundedCornerShape(12.sdp),
                tonalElevation = 2.sdp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ModelDownloadRow(
                        state = whisperState,
                        modelSize = whisperSize,
                        accent = topBarColor,
                        strings = s,
                        onDownload = viewModel::downloadWhisperModel,
                        onCancel = viewModel::cancelWhisperDownload,
                        onDelete = viewModel::deleteWhisperModel,
                        title = s.transcriptionModel,
                        description = s.transcriptionModelDescription,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.sdp))

            LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = 16.sdp,
                    vertical = 4.sdp
                ),
                verticalArrangement = Arrangement.spacedBy(4.sdp)
            ) {
                items(filteredLanguages, key = { it.code }) { language ->
                    val isSelected = currentLang == language.code
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setLanguage(language.code)
                            },
                        shape = RoundedCornerShape(10.sdp),
                        tonalElevation = if (isSelected) 4.sdp else 1.sdp,
                        color = if (isSelected)
                            topBarColor.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.sdp, vertical = 14.sdp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = language.nameNative,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.ssp,
                                    color = if (isSelected) topBarColor else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = language.nameEnglish,
                                    fontSize = 12.ssp,
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
