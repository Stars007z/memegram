package com.example.memegram

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.memegram.localization.LocalStrings
import com.example.memegram.utils.ImageTopAppBarBox
import com.example.memegram.utils.resolveTopBarTextColor
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    topBarColor: Color,
    onBack: () -> Unit,
    onChatClick: (conversationId: String, chatName: String, avatarMediaId: String) -> Unit,
    viewModel: StorageViewModel
) {
    val s = LocalStrings.current
    val topBarTextColor = resolveTopBarTextColor(topBarColor)

    val isLoading by viewModel.isLoading.collectAsState()
    val totalSize by viewModel.totalSize.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategories by viewModel.selectedCategories.collectAsState()
    val selectedSize by viewModel.selectedSize.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val chatList by viewModel.filteredChatList.collectAsState()
    val autoRemovePrivate by viewModel.autoRemovePrivateMs.collectAsState()
    val autoRemoveGroup by viewModel.autoRemoveGroupMs.collectAsState()
    val maxCacheSize by viewModel.maxCacheSizeBytes.collectAsState()
    val cleanupStrategy by viewModel.cleanupStrategy.collectAsState()
    val fifoLimit by viewModel.fifoLimit.collectAsState()
    val ttlDays by viewModel.ttlDays.collectAsState()
    val lruLimit by viewModel.lruLimit.collectAsState()
    val lfuLimit by viewModel.lfuLimit.collectAsState()

    var showAdvancedCleanup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadStorageOverview() }

    Scaffold(
        topBar = {
            ImageTopAppBarBox(topBarColor) { bgColor ->
                TopAppBar(
                    title = { Text(s.storageUsage) },
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
                        StorageDonutChart(
                            categories = categories,
                            selectedTypes = selectedCategories,
                            totalSize = totalSize
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.sdp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            s.storageUsage,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.sdp))
                        Text(
                            s.storageUsageOnDevice(formatSizeBytes(totalSize)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.sdp))
                    }
                }

                items(categories, key = { it.type }) { cat ->
                    StorageCategoryRow(
                        category = cat,
                        isSelected = cat.type in selectedCategories,
                        onToggle = { viewModel.toggleCategory(cat.type) }
                    )
                }

                item {
                    Spacer(Modifier.height(12.sdp))
                    Button(
                        onClick = { viewModel.clearSelectedCategories() },
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
                            s.clearSelectedSize(formatSizeBytes(selectedSize)),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                item {
                    val profilesSize by viewModel.profilesCacheSize.collectAsState()
                    Spacer(Modifier.height(12.sdp))
                    OutlinedButton(
                        onClick = { viewModel.clearProfilesCache() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.sdp)
                            .height(48.sdp),
                        shape = RoundedCornerShape(12.sdp),
                        enabled = profilesSize > 0
                    ) {
                        Icon(Icons.Default.PersonOutline, null, modifier = Modifier.size(18.sdp))
                        Spacer(Modifier.width(8.sdp))
                        Text(
                            if (profilesSize > 0)
                                s.clearProfilesCacheSize(formatSizeBytes(profilesSize))
                            else s.clearProfilesCache,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        s.profilesCacheInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.sdp, vertical = 8.sdp)
                    )
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

                item {
                    AutoRemoveSection(
                        privateValue = autoRemovePrivate,
                        groupValue = autoRemoveGroup,
                        onPrivateChange = viewModel::setAutoRemovePrivate,
                        onGroupChange = viewModel::setAutoRemoveGroup
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.sdp))
                }

                item {
                    CacheSizeSection(
                        currentLimit = maxCacheSize,
                        onLimitChange = viewModel::setMaxCacheSize
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.sdp))
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedCleanup = !showAdvancedCleanup }
                            .padding(horizontal = 16.sdp, vertical = 12.sdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.advancedCleanup,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (showAdvancedCleanup) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    AnimatedVisibility(visible = showAdvancedCleanup) {
                        Column {
                            AdvancedCleanupSection(
                                selectedStrategy = cleanupStrategy,
                                fifoLimit = fifoLimit,
                                ttlDays = ttlDays,
                                lruLimit = lruLimit,
                                lfuLimit = lfuLimit,
                                onStrategyChange = viewModel::setCleanupStrategy,
                                onFifoChange = viewModel::updateFifoLimit,
                                onTtlChange = viewModel::updateTtlDays,
                                onLruChange = viewModel::updateLruLimit,
                                onLfuChange = viewModel::updateLfuLimit
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.sdp))
                }

                stickyHeader {
                    StorageTabRow(
                        selectedTab = selectedTab,
                        onTabSelected = viewModel::setSelectedTab
                    )
                }

                items(chatList, key = { it.conversationId }) { chat ->
                    ChatStorageRow(
                        chat = chat,
                        onClick = {
                            onChatClick(
                                chat.conversationId,
                                chat.chatName,
                                chat.avatarMediaId ?: ""
                            )
                        }
                    )
                }

                if (chatList.isEmpty()) {
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
                }
            }
        }
    }
}

@Composable
private fun StorageDonutChart(
    categories: List<StorageCategoryUi>,
    selectedTypes: Set<String>,
    totalSize: Long,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val allNonEmpty = remember(categories) {
        categories.filter { it.sizeBytes > 0 }.sortedBy { it.type }
    }
    val selectedTotal = remember(categories, selectedTypes) {
        categories.filter { it.type in selectedTypes && it.sizeBytes > 0 }.sumOf { it.sizeBytes }
    }
    val displayedSize = selectedTotal

    val animatedWeights: Map<String, Float> = allNonEmpty.associate { cat ->
        val target = if (cat.type in selectedTypes) 1f else 0f
        val anim by animateFloatAsState(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = 220f
            ),
            label = "donutWeight_${cat.type}"
        )
        cat.type to anim
    }

    val emptyAlpha by animateFloatAsState(
        targetValue = if (selectedTotal == 0L) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 200f),
        label = "donutEmptyAlpha"
    )

    val (valueStr, unitStr) = remember(displayedSize) { formatSizeComponents(displayedSize) }

    val strokeWidthDp = 22.sdp
    val chartSize = 210.sdp
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Box(modifier = modifier.size(chartSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = strokeWidthDp.toPx()
            val padding = strokeWidth / 2 + 8f
            val diameter = size.minDimension - padding * 2
            val radius = diameter / 2
            val cx = size.width / 2
            val cy = size.height / 2

            val effectiveValues = allNonEmpty.map { cat ->
                (cat.sizeBytes.toDouble()) * (animatedWeights[cat.type] ?: 0f)
            }
            val effectiveTotal = effectiveValues.sum()

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(diameter, diameter)
            )

            if (emptyAlpha > 0.001f) {
                drawArc(
                    color = placeholderColor.copy(alpha = placeholderColor.alpha * emptyAlpha),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(diameter, diameter)
                )
            }

            if (effectiveTotal > 0.0) {
                val visibleCount = effectiveValues.count { it > 0.0001 }
                val gapDegrees = 0f
                val totalGap = gapDegrees * visibleCount
                val available = (360f - totalGap).coerceAtLeast(0f)
                val minSweep = if (visibleCount > 1) 3f else 0f
                val cap = if (visibleCount > 1) StrokeCap.Butt else StrokeCap.Round

                val rawSweeps = effectiveValues.map { v ->
                    if (v > 0.0) (v / effectiveTotal).toFloat() * available else 0f
                }
                val adjusted = rawSweeps.map {
                    if (it > 0f) it.coerceAtLeast(minSweep) else 0f
                }.toMutableList()
                val overflow = adjusted.sum() - available
                if (overflow > 0f) {
                    val reducible = adjusted.mapIndexedNotNull { i, v ->
                        val extra = v - minSweep
                        if (extra > 0f) i to extra else null
                    }
                    val reducibleSum = reducible.sumOf { it.second.toDouble() }.toFloat()
                    if (reducibleSum > 0f) {
                        reducible.forEach { (i, extra) ->
                            adjusted[i] = adjusted[i] - overflow * (extra / reducibleSum)
                        }
                    }
                }

                var startAngle = -90f
                allNonEmpty.forEachIndexed { idx, cat ->
                    val sweep = adjusted[idx]
                    if (sweep <= 0f) return@forEachIndexed
                    val w = animatedWeights[cat.type] ?: 0f
                    val gap = 0f
                    drawArc(
                        color = cat.color.copy(alpha = w.coerceIn(0f, 1f)),
                        startAngle = startAngle + gap / 2,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = cap),
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(diameter, diameter)
                    )
                    startAngle += sweep + gap
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.animateContentSize(
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 260f)
            )
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = valueStr,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    ) + androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(220)
                    ) { it / 3 }) togetherWith
                        (androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(150)
                        ) + androidx.compose.animation.slideOutVertically(
                            animationSpec = androidx.compose.animation.core.tween(220)
                        ) { -it / 3 })
                },
                label = "donutValue"
            ) { v ->
                Text(
                    v,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = onSurface
                )
            }
            androidx.compose.animation.AnimatedContent(
                targetState = unitStr,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    ) togetherWith androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(150)
                    )
                },
                label = "donutUnit"
            ) { u ->
                Text(
                    u,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceVariant
                )
            }
        }
    }
}


@Composable
private fun StorageCategoryRow(
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
private fun AutoRemoveSection(
    privateValue: Long,
    groupValue: Long,
    onPrivateChange: (Long) -> Unit,
    onGroupChange: (Long) -> Unit
) {
    val s = LocalStrings.current

    Column(modifier = Modifier.padding(horizontal = 16.sdp, vertical = 8.sdp)) {
        Text(
            s.autoRemoveMedia,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.sdp)
        )

        AutoRemoveRow(
            icon = {
                Box(
                    Modifier.size(36.sdp).clip(CircleShape)
                        .background(Color(0xFF5B8DEF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(20.sdp))
                }
            },
            label = s.privateChats,
            currentValue = periodToString(privateValue),
            onPeriodChange = onPrivateChange
        )

        AutoRemoveRow(
            icon = {
                Box(
                    Modifier.size(36.sdp).clip(CircleShape)
                        .background(Color(0xFF66BB6A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Groups, null, tint = Color.White, modifier = Modifier.size(20.sdp))
                }
            },
            label = s.groups,
            currentValue = periodToString(groupValue),
            onPeriodChange = onGroupChange
        )

        Spacer(Modifier.height(8.sdp))
        Text(
            s.autoRemoveInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AutoRemoveRow(
    icon: @Composable () -> Unit,
    label: String,
    currentValue: String,
    onPeriodChange: (Long) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showMenu = true }
            .padding(vertical = 10.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.sdp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            currentValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            val options = listOf(
                AutoRemovePeriod.NEVER to periodToString(AutoRemovePeriod.NEVER),
                AutoRemovePeriod.ONE_DAY to periodToString(AutoRemovePeriod.ONE_DAY),
                AutoRemovePeriod.ONE_WEEK to periodToString(AutoRemovePeriod.ONE_WEEK),
                AutoRemovePeriod.ONE_MONTH to periodToString(AutoRemovePeriod.ONE_MONTH),
                AutoRemovePeriod.THREE_MONTHS to periodToString(AutoRemovePeriod.THREE_MONTHS),
            )
            options.forEach { (ms, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPeriodChange(ms)
                        showMenu = false
                    }
                )
            }
        }
    }
}

@Composable
private fun periodToString(ms: Long): String {
    val s = LocalStrings.current
    return when (ms) {
        AutoRemovePeriod.NEVER -> s.never
        AutoRemovePeriod.ONE_DAY -> s.autoDelete1Day
        AutoRemovePeriod.ONE_WEEK -> s.autoDelete1Week
        AutoRemovePeriod.ONE_MONTH -> s.autoDelete1Month
        AutoRemovePeriod.THREE_MONTHS -> s.days3Months
        else -> s.never
    }
}

@Composable
private fun CacheSizeSection(
    currentLimit: Long,
    onLimitChange: (Long) -> Unit
) {
    val s = LocalStrings.current
    val presets = CacheSizeLimit.presets
    val currentIndex = presets.indexOf(currentLimit).takeIf { it >= 0 } ?: (presets.size - 1)

    fun labelFor(v: Long): String = when (v) {
        CacheSizeLimit.NO_LIMIT -> s.noLimit
        CacheSizeLimit.GB_1  -> "1 GB"
        CacheSizeLimit.GB_2  -> "2 GB"
        CacheSizeLimit.GB_5  -> "5 GB"
        CacheSizeLimit.GB_10 -> "10 GB"
        CacheSizeLimit.GB_16 -> "16 GB"
        CacheSizeLimit.GB_32 -> "32 GB"
        CacheSizeLimit.GB_64 -> "64 GB"
        else -> formatSizeBytes(v)
    }

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val trackInactive = MaterialTheme.colorScheme.surfaceVariant
    val targetFraction = if (presets.size > 1) currentIndex.toFloat() / (presets.size - 1) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = 300f
        ),
        label = "cacheSliderFraction"
    )

    Column(modifier = Modifier.padding(horizontal = 16.sdp, vertical = 8.sdp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                s.maxCacheSize,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.sdp))
                    .background(primary.copy(alpha = 0.12f))
                    .padding(horizontal = 10.sdp, vertical = 4.sdp)
            ) {
                Text(
                    labelFor(currentLimit),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = primary
                )
            }
        }

        Spacer(Modifier.height(16.sdp))

        val thumbRadiusDp = 12.sdp
        val trackHeightDp = 8.sdp
        val tickRadiusDp = 3.sdp

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.sdp)
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val thumbRadiusPx = with(density) { thumbRadiusDp.toPx() }
            val trackStartPx = thumbRadiusPx
            val trackEndPx = widthPx - thumbRadiusPx
            val trackSpan = (trackEndPx - trackStartPx).coerceAtLeast(1f)

            fun indexFromX(x: Float): Int {
                val clamped = (x - trackStartPx).coerceIn(0f, trackSpan)
                val frac = clamped / trackSpan
                return (frac * (presets.size - 1)).roundToInt().coerceIn(0, presets.size - 1)
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(presets.size) {
                        detectTapGestures { offset ->
                            val idx = indexFromX(offset.x)
                            onLimitChange(presets[idx])
                        }
                    }
                    .pointerInput(presets.size) {
                        detectDragGestures { change, _ ->
                            val idx = indexFromX(change.position.x)
                            val newValue = presets[idx]
                            if (newValue != currentLimit) onLimitChange(newValue)
                        }
                    }
            ) {
                val cy = size.height / 2
                val thumbR = thumbRadiusPx
                val trackH = trackHeightDp.toPx()
                val tickR = tickRadiusDp.toPx()
                val startX = thumbR
                val endX = size.width - thumbR
                val span = endX - startX
                val thumbX = startX + span * animatedFraction

                drawRoundRect(
                    color = trackInactive,
                    topLeft = Offset(startX, cy - trackH / 2),
                    size = Size(span, trackH),
                    cornerRadius = CornerRadius(trackH / 2)
                )

                if (thumbX > startX) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                primary.copy(alpha = 0.85f),
                                primary
                            ),
                            startX = startX,
                            endX = thumbX
                        ),
                        topLeft = Offset(startX, cy - trackH / 2),
                        size = Size(thumbX - startX, trackH),
                        cornerRadius = CornerRadius(trackH / 2)
                    )
                }

                presets.forEachIndexed { i, _ ->
                    val tickX = startX + span * (i.toFloat() / (presets.size - 1))
                    val active = tickX <= thumbX + 0.5f
                    drawCircle(
                        color = if (active) onPrimary.copy(alpha = 0.9f) else primary.copy(alpha = 0.35f),
                        radius = tickR,
                        center = Offset(tickX, cy)
                    )
                }

                drawCircle(
                    color = Color.Black.copy(alpha = 0.18f),
                    radius = thumbR + 2f,
                    center = Offset(thumbX, cy + 2f)
                )
                drawCircle(color = primary, radius = thumbR, center = Offset(thumbX, cy))
                drawCircle(color = onPrimary, radius = thumbR * 0.35f, center = Offset(thumbX, cy))
            }
        }

        Spacer(Modifier.height(8.sdp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            presets.forEach { v ->
                val isSelected = v == currentLimit
                Text(
                    labelFor(v),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.sdp))
                        .clickable { onLimitChange(v) }
                        .padding(horizontal = 4.sdp, vertical = 2.sdp)
                )
            }
        }

        Spacer(Modifier.height(8.sdp))
        Text(
            s.cacheLimitInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StorageTabRow(
    selectedTab: StorageTab,
    onTabSelected: (StorageTab) -> Unit
) {
    val s = LocalStrings.current
    val tabs = listOf(
        StorageTab.CHATS to s.chatsTab,
        StorageTab.MEDIA to s.mediaTab,
        StorageTab.FILES to s.filesTab,
        StorageTab.MUSIC to s.musicTab
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
private fun ChatStorageRow(
    chat: ChatStorageUi,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.sdp, vertical = 10.sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            mediaId = chat.avatarMediaId,
            size = 48.sdp,
            fallbackLetter = chat.chatName.firstOrNull()?.uppercase() ?: "?"
        )

        Spacer(Modifier.width(12.sdp))

        Text(
            chat.chatName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(Modifier.width(8.sdp))

        Text(
            formatSizeBytes(chat.totalSize),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun AdvancedCleanupSection(
    selectedStrategy: String,
    fifoLimit: Long,
    ttlDays: Long,
    lruLimit: Long,
    lfuLimit: Long,
    onStrategyChange: (String) -> Unit,
    onFifoChange: (Long) -> Unit,
    onTtlChange: (Long) -> Unit,
    onLruChange: (Long) -> Unit,
    onLfuChange: (Long) -> Unit
) {
    val s = LocalStrings.current

    Column(modifier = Modifier.animateContentSize()) {
        when (selectedStrategy) {
            "FIFO" -> StrategyParamSlider(
                label = s.messagesInChat, description = s.fifoDescription,
                value = fifoLimit, min = 100L, max = 10_000L, step = 100L,
                onChanged = onFifoChange
            )
            "TTL" -> StrategyParamSlider(
                label = s.storeMessagesDays, description = s.ttlDescription,
                value = ttlDays, min = 1L, max = 365L, step = 1L,
                onChanged = onTtlChange
            )
            "LRU" -> StrategyParamSlider(
                label = s.globalLimit, description = s.lruDescription,
                value = lruLimit, min = 500L, max = 50_000L, step = 500L,
                onChanged = onLruChange
            )
            "LFU" -> StrategyParamSlider(
                label = s.globalLimit, description = s.lfuDescription,
                value = lfuLimit, min = 500L, max = 50_000L, step = 500L,
                onChanged = onLfuChange
            )
        }

        val options = listOf(
            "TTL" to s.deleteOldByTime,
            "LRU" to s.deleteLeastRecent,
            "LFU" to s.deleteLeastRequested,
            "FIFO" to s.deleteOldestFifo
        )
        options.forEach { (key, title) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStrategyChange(key) }
                    .padding(horizontal = 16.sdp, vertical = 10.sdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedStrategy == key,
                    onClick = { onStrategyChange(key) }
                )
                Spacer(Modifier.width(12.sdp))
                Text(title, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun StrategyParamSlider(
    label: String,
    description: String,
    value: Long,
    min: Long,
    max: Long,
    step: Long,
    onChanged: (Long) -> Unit
) {
    var inputText by remember(value) { mutableStateOf(value.toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.sdp, vertical = 8.sdp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.sdp)
            )
            .padding(12.sdp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = inputText,
                onValueChange = { raw ->
                    inputText = raw
                    raw.toLongOrNull()?.coerceIn(min, max)?.let { onChanged(it) }
                },
                modifier = Modifier.width(96.sdp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChanged(it.toLong()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / step - 1).toInt().coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "$min", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 8.sdp),
                textAlign = TextAlign.Center
            )
            Text(
                "$max", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
