package com.example.memegram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.ImageTopAppBarBox
import com.example.memegram.utils.sdp
import kotlin.math.roundToInt


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatStorageDetailScreen(
    topBarColor: Color,
    conversationId: String,
    chatName: String,
    avatarMediaId: String,
    onBack: () -> Unit,
    viewModel: StorageViewModel
) {
    val s = LocalStrings.current
    val topBarTextColor = if (topBarColor.luminance() < 0.5f) Color.White else Color.Black

    val isLoading by viewModel.isLoading.collectAsState()
    val categories by viewModel.chatDetailCategories.collectAsState()
    val selectedCategories by viewModel.chatDetailSelectedCategories.collectAsState()
    val selectedSize by viewModel.chatDetailSelectedSize.collectAsState()
    val totalSize by viewModel.chatDetailTotalSize.collectAsState()
    val selectedTab by viewModel.chatDetailSelectedTab.collectAsState()
    val mediaItems by viewModel.filteredMediaItems.collectAsState()

    LaunchedEffect(conversationId) { viewModel.loadChatDetail(conversationId) }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
                TopAppBar(
                    title = {
                        Text(
                            chatName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 32.sdp)
            ) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.sdp),
                        contentAlignment = Alignment.Center
                    ) {
                        ChatDetailDonutChart(
                            categories = categories,
                            totalSize = totalSize,
                            avatarMediaId = avatarMediaId,
                            chatName = chatName
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.sdp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            chatName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.sdp))
                        Text(
                            formatSizeBytes(totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.sdp))
                    }
                }

                items(categories, key = { it.type }) { cat ->
                    ChatDetailCategoryRow(
                        category = cat,
                        isSelected = cat.type in selectedCategories,
                        onToggle = { viewModel.toggleChatDetailCategory(cat.type) }
                    )
                }

                item {
                    Spacer(Modifier.height(12.sdp))
                    Button(
                        onClick = { viewModel.clearChatDetailSelectedCategories(conversationId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.sdp)
                            .height(48.sdp),
                        shape = RoundedCornerShape(12.sdp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = selectedSize > 0
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(18.sdp))
                        Spacer(Modifier.width(8.sdp))
                        Text(
                            s.clearCacheSize(formatSizeBytes(selectedSize)),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                item {
                    Text(
                        s.storageCloudInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.sdp, vertical = 12.sdp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.sdp))
                }

                stickyHeader {
                    ChatDetailTabRow(
                        selectedTab = selectedTab,
                        onTabSelected = viewModel::setChatDetailTab
                    )
                }

                if (mediaItems.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.sdp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                s.nothingFound,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    when (selectedTab) {
                        ChatDetailTab.MEDIA -> {
                            val chunked = mediaItems.chunked(3)
                            items(chunked.size, key = { it }) { index ->
                                val row = chunked[index]
                                MediaGridRow(items = row)
                            }
                        }
                        ChatDetailTab.FILES -> {
                            items(mediaItems, key = { it.serverId }) { item ->
                                FileListItem(item = item)
                            }
                        }
                        ChatDetailTab.MUSIC -> {
                            items(mediaItems, key = { it.serverId }) { item ->
                                MusicListItem(item = item)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun ChatDetailDonutChart(
    categories: List<StorageCategoryUi>,
    totalSize: Long,
    avatarMediaId: String,
    chatName: String,
    modifier: Modifier = Modifier
) {
    val strokeWidthDp = 22.sdp
    val chartSize = 210.sdp
    val avatarSize = 100.sdp

    Box(modifier = modifier.size(chartSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = strokeWidthDp.toPx()
            val padding = strokeWidth / 2 + 8f
            val diameter = size.minDimension - padding * 2
            val radius = diameter / 2
            val cx = size.width / 2
            val cy = size.height / 2

            val selected = categories.filter { it.isSelected && it.sizeBytes > 0 }
            val gapDegrees = if (selected.size > 1) 3f else 0f
            val minSweep = 6f

            if (selected.isEmpty()) {
                drawArc(
                    color = Color.Gray.copy(alpha = 0.3f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(diameter, diameter)
                )
            } else {
                val selectedTotal = selected.sumOf { it.sizeBytes }.toFloat()
                val totalGap = gapDegrees * selected.size
                val available = 360f - totalGap
                val rawSweeps = selected.map { (it.sizeBytes.toFloat() / selectedTotal) * available }
                val adjusted = rawSweeps.map { it.coerceAtLeast(minSweep) }.toMutableList()
                val overflow = adjusted.sum() - available
                if (overflow > 0f) {
                    val reducible = adjusted.mapIndexed { i, v -> i to (v - minSweep) }.filter { it.second > 0f }
                    val reducibleSum = reducible.sumOf { it.second.toDouble() }.toFloat()
                    if (reducibleSum > 0f) {
                        reducible.forEach { (i, extra) ->
                            adjusted[i] = adjusted[i] - overflow * (extra / reducibleSum)
                        }
                    }
                }
                var startAngle = -90f
                selected.forEachIndexed { idx, cat ->
                    val sweep = adjusted[idx]
                    drawArc(
                        color = cat.color,
                        startAngle = startAngle + gapDegrees / 2,
                        sweepAngle = (sweep - gapDegrees).coerceAtLeast(0.5f),
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(diameter, diameter)
                    )
                    startAngle += sweep + gapDegrees
                }
            }
        }

        AvatarImage(
            mediaId = avatarMediaId.ifEmpty { null },
            size = avatarSize,
            fallbackLetter = chatName.firstOrNull()?.uppercase() ?: "?"
        )
    }
}


@Composable
private fun ChatDetailCategoryRow(
    category: StorageCategoryUi,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.sdp, vertical = 10.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.sdp)
                .clip(CircleShape)
                .background(if (isSelected) category.color else Color.Transparent)
                .then(
                    if (!isSelected) Modifier.background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(16.sdp)
                )
            }
        }

        Spacer(Modifier.width(12.sdp))

        Text(
            displayNameForType(category.type),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )

        if (category.percentage >= 1f) {
            Text(
                "${category.percentage.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.sdp)
            )
        } else if (category.sizeBytes > 0) {
            Text(
                "<1%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.sdp)
            )
        }

        Text(
            formatSizeBytes(category.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}


@Composable
private fun ChatDetailTabRow(
    selectedTab: ChatDetailTab,
    onTabSelected: (ChatDetailTab) -> Unit
) {
    val s = LocalStrings.current
    val tabs = listOf(
        ChatDetailTab.MEDIA to s.mediaTab,
        ChatDetailTab.FILES to s.filesTab,
        ChatDetailTab.MUSIC to s.musicTab
    )

    Surface(color = MaterialTheme.colorScheme.surface) {
        TabRow(
            selectedTabIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEach { (tab, label) ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            label,
                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }
    }
}


@Composable
private fun MediaGridRow(items: List<MediaItemInfo>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.sdp),
        horizontalArrangement = Arrangement.spacedBy(2.sdp)
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.sdp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (item.previewBytes != null) {
                    val bitmap = remember(item.serverId) {
                        runCatching { item.previewBytes.decodeToImageBitmap() }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        MediaPlaceholderIcon(item.type)
                    }
                } else {
                    MediaPlaceholderIcon(item.type)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.sdp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(4.sdp)
                        )
                        .padding(horizontal = 4.sdp, vertical = 2.sdp)
                ) {
                    Text(
                        formatSizeBytes(item.estimatedSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }

        repeat(3 - items.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MediaPlaceholderIcon(type: String) {
    val icon = when (type) {
        "photo", "video" -> Icons.Default.InsertDriveFile
        "music" -> Icons.Default.MusicNote
        else -> Icons.Default.InsertDriveFile
    }
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(32.sdp)
    )
}


@Composable
private fun FileListItem(item: MediaItemInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.sdp, vertical = 10.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.sdp)
                .clip(RoundedCornerShape(8.sdp))
                .background(StorageColorDocuments.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = StorageColorDocuments,
                modifier = Modifier.size(24.sdp)
            )
        }

        Spacer(Modifier.width(12.sdp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.mediaId ?: item.serverId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatSizeBytes(item.estimatedSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun MusicListItem(item: MediaItemInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.sdp, vertical = 10.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.sdp)
                .clip(RoundedCornerShape(8.sdp))
                .background(StorageColorMusic.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = StorageColorMusic,
                modifier = Modifier.size(24.sdp)
            )
        }

        Spacer(Modifier.width(12.sdp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.mediaId ?: item.serverId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatSizeBytes(item.estimatedSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
