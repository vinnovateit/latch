package com.vinnovateit.latch.ui.screens.stats.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.HistoryChartItem
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.formatDurationDynamic
import com.vinnovateit.latch.ui.components.LatchIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Calendar

@Immutable
data class DesktopChartDetailState(
    val usage: DataUsage,
    val label: String,
    val sessionCount: Int = 0,
    val durationFormatted: String = "",
)

@Composable
fun HistoryBarChart(
    chartItems: List<HistoryChartItem>,
    dlColor: Color,
    ulColor: Color,
    isAmoled: Boolean,
    onSelectedDayChange: ((Long?) -> Unit)? = null,
) {
    if (chartItems.isEmpty()) return

    val todayIdx = remember(chartItems) {
        val todayKey = formatDate(System.currentTimeMillis(), "yyyy-MM-dd")
        val exact = chartItems.indexOfLast {
            it is HistoryChartItem.BarData && formatDate(it.timestamp, "yyyy-MM-dd") == todayKey
        }
        val idx = if (exact != -1) exact else chartItems.indexOfLast { it is HistoryChartItem.BarData }
        idx.coerceAtLeast(0)
    }

    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = todayIdx)
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val (totalUsageDetail, _) = remember(chartItems) {
        var rx = 0L
        var tx = 0L
        var sessions = 0
        var durationMs = 0L
        var maxVal = 1L
        for (item in chartItems) {
            if (item is HistoryChartItem.BarData) {
                val tot = item.usage.rxBytes + item.usage.txBytes
                rx += item.usage.rxBytes
                tx += item.usage.txBytes
                sessions += item.sessionCount
                durationMs += item.durationMillis
                if (tot > maxVal) maxVal = tot
            }
        }
        val detail = DesktopChartDetailState(
            usage = DataUsage(rx, tx),
            label = "Total Data Usage",
            sessionCount = sessions,
            durationFormatted = formatDurationDynamic(durationMs),
        )
        Pair(detail, maxVal)
    }

    val initialBarItem = remember(chartItems, todayIdx) {
        chartItems.getOrNull(todayIdx) as? HistoryChartItem.BarData
    }
    LaunchedEffect(initialBarItem) {
        onSelectedDayChange?.invoke(initialBarItem?.timestamp)
    }
    var selectedIndex by remember(chartItems, todayIdx) {
        mutableIntStateOf(if (initialBarItem != null) todayIdx else -1)
    }
    var displayedData by remember(chartItems, todayIdx) {
        mutableStateOf(
            if (initialBarItem != null) {
                DesktopChartDetailState(
                    usage = initialBarItem.usage,
                    label = initialBarItem.formattedDate.ifBlank {
                        formatDate(initialBarItem.timestamp, "EEEE, MMMM d, yyyy")
                    },
                    sessionCount = initialBarItem.sessionCount,
                    durationFormatted = initialBarItem.durationFormatted,
                )
            } else {
                totalUsageDetail
            }
        )
    }

    var visibleMaxUsage by remember { mutableLongStateOf(1L) }
    LaunchedEffect(chartItems, lazyListState) {
        snapshotFlow {
            val visible = lazyListState.layoutInfo.visibleItemsInfo
            var maxV = 1L
            for (v in visible) {
                val item = chartItems.getOrNull(v.index)
                if (item is HistoryChartItem.BarData) {
                    val tot = item.usage.rxBytes + item.usage.txBytes
                    if (tot > maxV) maxV = tot
                }
            }
            maxV
        }.distinctUntilChanged().collect {
            visibleMaxUsage = it
        }
    }

    var lastCenteredIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(chartItems, lazyListState) {
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val visibleBars = layoutInfo.visibleItemsInfo.filter {
                chartItems.getOrNull(it.index) is HistoryChartItem.BarData
            }
            visibleBars.minByOrNull { item ->
                val itemCenter = item.offset + item.size / 2
                kotlin.math.abs(itemCenter - viewportCenter)
            }?.index ?: -1
        }.distinctUntilChanged().collect { centerIdx ->
            if (centerIdx != -1 && centerIdx != lastCenteredIndex && lazyListState.isScrollInProgress) {
                val item = chartItems.getOrNull(centerIdx) as? HistoryChartItem.BarData
                if (item != null) {
                    lastCenteredIndex = centerIdx
                    selectedIndex = centerIdx
                    displayedData = DesktopChartDetailState(
                        usage = item.usage,
                        label = item.formattedDate.ifBlank {
                            formatDate(item.timestamp, "EEEE, MMMM d, yyyy")
                        },
                        sessionCount = item.sessionCount,
                        durationFormatted = item.durationFormatted,
                    )
                    onSelectedDayChange?.invoke(item.timestamp)
                }
            }
        }
    }

    val animatedMaxUsage by animateFloatAsState(
        targetValue = visibleMaxUsage.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "DesktopBarMaxUsageSpring",
    )

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val headerTitle by remember(chartItems, lazyListState) {
        derivedStateOf {
            val visible = lazyListState.layoutInfo.visibleItemsInfo
            val centerItem = if (visible.isNotEmpty()) {
                val centerOffset = (lazyListState.layoutInfo.viewportStartOffset + lazyListState.layoutInfo.viewportEndOffset) / 2
                visible.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - centerOffset) }
            } else null

            val targetItem = (centerItem?.index?.let { chartItems.getOrNull(it) })
                ?: chartItems.getOrNull(todayIdx)

            val timestamp = when (targetItem) {
                is HistoryChartItem.BarData -> targetItem.timestamp
                is HistoryChartItem.CollapsedMonth -> targetItem.timestamp
                else -> System.currentTimeMillis()
            }
            val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
            val calYear = cal.get(Calendar.YEAR)
            val monthName = java.text.SimpleDateFormat("MMMM", java.util.Locale.US).format(cal.time)
            if (calYear == currentYear) monthName else "$monthName $calYear"
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = headerTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val barWidth = 14.dp
            val rowHeight = 160.dp
            val barAreaHeight = 160.dp
            val centerPadding = ((maxWidth - barWidth) / 2).coerceAtLeast(16.dp)

            LazyRow(
                state = lazyListState,
                modifier = Modifier.height(rowHeight),
                contentPadding = PaddingValues(horizontal = centerPadding),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = lazyListState),
                horizontalArrangement = Arrangement.spacedBy(0.2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                itemsIndexed(
                    items = chartItems,
                    key = { index, item ->
                        when (item) {
                            is HistoryChartItem.BarData -> "bar_${item.timestamp}_$index"
                            is HistoryChartItem.MonthSeparator -> "month_${item.monthName}_$index"
                            is HistoryChartItem.CollapsedMonth -> "collapsed_${item.monthName}_$index"
                        }
                    },
                ) { idx, item ->
                    when (item) {
                        is HistoryChartItem.BarData -> {
                            DesktopCanvasBar(
                                modifier = Modifier.width(barWidth).fillMaxHeight(),
                                usage = item.usage,
                                maxUsage = { animatedMaxUsage },
                                isSelected = (idx == selectedIndex),
                                hasSelection = (selectedIndex != -1),
                                isAmoled = isAmoled,
                                barWidth = barWidth,
                                barAreaHeight = barAreaHeight,
                                dlColor = dlColor,
                                ulColor = ulColor,
                                onTap = {
                                    lastCenteredIndex = idx
                                    selectedIndex = idx
                                    displayedData = DesktopChartDetailState(
                                        usage = item.usage,
                                        label = item.formattedDate.ifBlank {
                                            formatDate(item.timestamp, "EEEE, MMMM d, yyyy")
                                        },
                                        sessionCount = item.sessionCount,
                                        durationFormatted = item.durationFormatted,
                                    )
                                    onSelectedDayChange?.invoke(item.timestamp)
                                    coroutineScope.launch {
                                        val layoutInfo = lazyListState.layoutInfo
                                        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                                        if (targetItem != null) {
                                            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                                            val itemCenter = targetItem.offset + targetItem.size / 2
                                            val delta = (itemCenter - viewportCenter).toFloat()
                                            if (kotlin.math.abs(delta) > 1f) {
                                                lazyListState.animateScrollBy(
                                                    value = delta,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        is HistoryChartItem.MonthSeparator -> {
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = item.monthName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.rotate(-90f),
                                )
                            }
                        }
                        is HistoryChartItem.CollapsedMonth -> {
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = item.monthName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.rotate(-90f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        DesktopStatDetailRow(data = displayedData, dlColor = dlColor, ulColor = ulColor)
    }
}

@Composable
private fun DesktopCanvasBar(
    modifier: Modifier = Modifier,
    usage: DataUsage,
    maxUsage: () -> Float,
    isSelected: Boolean,
    hasSelection: Boolean = false,
    isAmoled: Boolean = false,
    barWidth: Dp,
    barAreaHeight: Dp,
    dlColor: Color,
    ulColor: Color,
    onTap: () -> Unit,
) {
    val total = usage.rxBytes + usage.txBytes
    val uploadFrac = if (total > 0) usage.txBytes.toFloat() / total.toFloat() else 0f
    val emptyColor = if (isAmoled) Color(0xFF262626) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Canvas(
        modifier = modifier
            .width(barWidth)
            .height(barAreaHeight)
            .graphicsLayer {
                alpha = if (isSelected) 1f else if (hasSelection) 0.45f else 1f
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
    ) {
        val maxVal = maxUsage()
        val currentFrac = if (maxVal > 0f && total > 0L) {
            (total.toFloat() / maxVal).coerceIn(0.04f, 0.96f)
        } else 0.04f
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
        val strokeWidth = 1.5.dp.toPx()
        val inset = if (isAmoled) strokeWidth / 2 else 0f
        val drawWidth = (size.width - inset * 2).coerceAtLeast(0f)

        if (total > 0) {
            val rawBarHeight = (size.height * currentFrac).coerceAtLeast(6.dp.toPx())
            val drawHeight = (rawBarHeight - inset * 2).coerceAtLeast(0f)
            val startY = size.height - rawBarHeight + inset
            val topLeftOffset = Offset(inset, startY)

            val ulH = if (uploadFrac > 0f) (drawHeight * uploadFrac).coerceAtLeast(2.dp.toPx()) else 0f
            val dlH = (drawHeight - ulH).coerceAtLeast(0f)

            if (ulH > 0f) {
                if (isAmoled) {
                    drawRoundRect(
                        color = ulColor,
                        topLeft = topLeftOffset,
                        size = Size(drawWidth, ulH),
                        cornerRadius = cornerRadius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
                    )
                } else {
                    drawRoundRect(
                        color = ulColor,
                        topLeft = topLeftOffset,
                        size = Size(drawWidth, ulH),
                        cornerRadius = cornerRadius,
                    )
                }
            }

            if (dlH > 0f) {
                val dlTopY = topLeftOffset.y + ulH
                if (isAmoled) {
                    drawRoundRect(
                        color = dlColor,
                        topLeft = Offset(topLeftOffset.x, dlTopY),
                        size = Size(drawWidth, dlH),
                        cornerRadius = cornerRadius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
                    )
                } else {
                    drawRoundRect(
                        color = dlColor,
                        topLeft = Offset(topLeftOffset.x, dlTopY),
                        size = Size(drawWidth, dlH),
                        cornerRadius = cornerRadius,
                    )
                }
            }
        } else {
            val rawBarHeight = 4.dp.toPx()
            val drawHeight = (rawBarHeight - inset * 2).coerceAtLeast(0f)
            val startY = size.height - rawBarHeight + inset
            val topLeftOffset = Offset(inset, startY)
            drawRoundRect(
                color = emptyColor,
                topLeft = topLeftOffset,
                size = Size(drawWidth, drawHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun DesktopStatDetailRow(
    data: DesktopChartDetailState,
    dlColor: Color,
    ulColor: Color,
) {
    val (currentUsage, label, sessionCount, durationFormatted) = data
    val (totalFmt, dlFmt, ulFmt) = remember(currentUsage) {
        Triple(
            formatBytes(currentUsage.rxBytes + currentUsage.txBytes),
            formatBytes(currentUsage.rxBytes),
            formatBytes(currentUsage.txBytes),
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "${totalFmt.first} ${totalFmt.second}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LatchIcons.ArrowDownward, null, tint = dlColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "${dlFmt.first} ${dlFmt.second}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LatchIcons.ArrowUpward, null, tint = ulColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "${ulFmt.first} ${ulFmt.second}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (sessionCount > 0 || (durationFormatted.isNotBlank() && durationFormatted != "0s")) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sessionCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        ) {
                            Text(
                                text = "$sessionCount ${if (sessionCount == 1) "session" else "sessions"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (durationFormatted.isNotBlank() && durationFormatted != "0s") {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = "⏱ $durationFormatted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
