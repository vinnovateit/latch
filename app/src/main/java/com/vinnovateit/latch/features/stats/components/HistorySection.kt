package com.vinnovateit.latch.features.stats.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.common.util.NoDataCard
import com.vinnovateit.latch.common.util.formatBytes
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.ui.theme.ColorGraphDownload
import com.vinnovateit.latch.ui.theme.ColorGraphUpload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.features.settings.manager.SettingsManager

@Immutable
sealed class HistoryChartItem {
    data class BarData(val usage: DataUsage, val label: String, val timestamp: Long) : HistoryChartItem()
    data class MonthSeparator(val monthName: String) : HistoryChartItem()
}

@Composable
fun HistoryBarChart(history: List<HistoryChartItem>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Daily Usage",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Left,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
        if (history.isNotEmpty()) {
            HistoryBarChartContent(chartItems = history)
        } else {
            NoDataCard("No session history available.")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryBarChartContent(chartItems: List<HistoryChartItem>) {

    if (chartItems.filterIsInstance<HistoryChartItem.BarData>().all { it.usage.rxBytes + it.usage.txBytes == 0L }) {
        NoDataCard("No history data yet.")
        return
    }

    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current


    val todayIdx = chartItems.indexOfLast { it is HistoryChartItem.BarData }
    var selectedIndex by remember { mutableIntStateOf(todayIdx) }

    // Add state to track if we are programmatically scrolling
    var isAutoScrolling by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dateFormatter = remember { SimpleDateFormat("E, dd MMM", Locale.getDefault()) }

    val totalUsageData = remember(chartItems) {
        val totalRx = chartItems.filterIsInstance<HistoryChartItem.BarData>().sumOf { it.usage.rxBytes }
        val totalTx = chartItems.filterIsInstance<HistoryChartItem.BarData>().sumOf { it.usage.txBytes }
        DataUsage(totalRx, totalTx)
    }
    val totalUsageLabel = "Total Data Usage"
    var displayedData by remember { mutableStateOf(totalUsageData to totalUsageLabel) }
    var revertJob by remember { mutableStateOf<Job?>(null) }

    val maxDailyUsage = remember(chartItems) {
        chartItems.filterIsInstance<HistoryChartItem.BarData>()
            .maxOfOrNull { it.usage.rxBytes + it.usage.txBytes }
            ?.coerceAtLeast(1L) ?: 1L
    }



    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.layoutInfo }
            .map { layoutInfo ->
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                layoutInfo.visibleItemsInfo.minByOrNull {
                    val itemCenter = it.offset + it.size / 2
                    abs(itemCenter - viewportCenter)
                }?.index ?: -1
            }
            .distinctUntilChanged()
            .collect { centerIndex ->
                // Only update selection if we are NOT in the middle of an auto-scroll
                if (!isAutoScrolling && centerIndex != -1 && selectedIndex != centerIndex) {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    selectedIndex = centerIndex

                    val item = chartItems[centerIndex]
                    if (item is HistoryChartItem.BarData) {
                        displayedData = item.usage to dateFormatter.format(Date(item.timestamp))
                        revertJob?.cancel()
                        revertJob = coroutineScope.launch {
                            delay(7000)
                            displayedData = totalUsageData to totalUsageLabel
                        }
                    }
                }
            }
    }

    // Initial scroll to today
    LaunchedEffect(Unit) {
        if (todayIdx != -1) {
            lazyListState.scrollToItem(todayIdx)
        }
        displayedData = totalUsageData to totalUsageLabel
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val halfScreenWidth = this.maxWidth / 2
            val barWidth = 52.dp // Made bars bigger
            val rowHeight = 220.dp // Increased height
            val barAreaHeight = rowHeight * 0.8f
            val horizontalPadding = halfScreenWidth - (barWidth / 2)

            LazyRow(
                state = lazyListState,
                modifier = Modifier.height(rowHeight),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                itemsIndexed(chartItems, key = { index, item ->
                    when (item) {
                        is HistoryChartItem.BarData -> "bar_${item.timestamp}"
                        is HistoryChartItem.MonthSeparator -> "month_${item.monthName}_$index"
                    }
                }) { idx, item ->
                    when (item) {
                        is HistoryChartItem.BarData -> {
                                Bar(
                                    modifier = Modifier
                                        .width(barWidth)
                                        .fillMaxHeight(),
                                    usage = item.usage,
                                    maxUsage = maxDailyUsage,
                                    dayLabel = item.label,
                                    isSelected = (idx == selectedIndex),
                                    isAmoled = isAmoled,
                                    barAreaHeight = barAreaHeight,
                                    onTap = {
                                    if (selectedIndex != idx) {
                                        // 1. Set flag to prevent intermediate snaps
                                        isAutoScrolling = true

                                        // 2. Update selection immediately
                                        selectedIndex = idx

                                        // 3. Update displayed data immediately
                                        displayedData = item.usage to dateFormatter.format(Date(item.timestamp))
                                        revertJob?.cancel()
                                        revertJob = coroutineScope.launch {
                                            delay(7000)
                                            displayedData = totalUsageData to totalUsageLabel
                                        }

                                        // 4. Perform the smooth scroll
                                        coroutineScope.launch {
                                            lazyListState.animateScrollToItem(idx)
                                            // 5. Reset flag after scroll completes
                                            isAutoScrolling = false
                                        }
                                    }
                                }
                            )
                        }
                        is HistoryChartItem.MonthSeparator -> {
                            MonthSeparator(monthName = item.monthName)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StatDetailRow(data = displayedData)
    }
}

@Composable
private fun MonthSeparator(monthName: String) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = monthName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(-90f)
        )
    }
}


@Composable
private fun Bar(
    modifier: Modifier = Modifier,
    usage: DataUsage,
    maxUsage: Long,
    dayLabel: String,
    isSelected: Boolean,
    isAmoled: Boolean = false,
    barAreaHeight: Dp,
    onTap: () -> Unit
) {
    val total = usage.rxBytes + usage.txBytes
    val rawFrac = if (maxUsage > 0) total.toFloat() / maxUsage else 0f
    val targetFrac = rawFrac.coerceAtLeast(0.15f)

    // Modified animation for "Go slow / Accelerate" and "Overshoot"
    val heightFrac by animateFloatAsState(
        targetValue = targetFrac,
        animationSpec = spring(
            dampingRatio = 0.45f, // Bouncy (Overshoot)
            stiffness = Spring.StiffnessLow // Slow start/settle
        ),
        label = "BarHeight"
    )

    val uploadFrac = if (total > 0) usage.txBytes.toFloat() / total else 0f
    val downloadFrac = 1f - uploadFrac
    val density = LocalDensity.current
    val barHeightInDp = with(density) { (barAreaHeight.toPx() * heightFrac).toDp() }

    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barAreaHeight),
            contentAlignment = Alignment.BottomCenter
        ) {
            val dlColor = ColorGraphDownload
            val ulColor = ColorGraphUpload

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeightInDp)
            ) {
                val strokeWidth = 4.dp.toPx()
                val cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2, size.width / 2)
                
                // Inset the drawing rect by half the stroke width when outlined so it doesn't get clipped by Canvas bounds.
                val inset = if (isAmoled) strokeWidth / 2 else 0f
                val drawSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeftOffset = Offset(inset, inset)

                if (total > 0) {
                    val gapPx = if (downloadFrac > 0f && uploadFrac > 0f) 4.dp.toPx() else 0f
                    val availableHeight = drawSize.height - gapPx
                    val ulH = availableHeight * uploadFrac
                    val dlH = availableHeight * downloadFrac

                    if (ulH > 0) {
                        if (isAmoled) {
                            drawRoundRect(
                                color = ulColor,
                                topLeft = topLeftOffset,
                                size = Size(drawSize.width, ulH),
                                cornerRadius = cornerRadius,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                            )
                        } else {
                            drawRoundRect(
                                color = ulColor,
                                topLeft = topLeftOffset,
                                size = Size(drawSize.width, ulH),
                                cornerRadius = cornerRadius
                            )
                        }
                    }

                    if (dlH > 0) {
                        val dlTopY = topLeftOffset.y + ulH + gapPx
                        if (isAmoled) {
                            drawRoundRect(
                                color = dlColor,
                                topLeft = Offset(topLeftOffset.x, dlTopY),
                                size = Size(drawSize.width, dlH),
                                cornerRadius = cornerRadius,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                            )
                        } else {
                            drawRoundRect(
                                color = dlColor,
                                topLeft = Offset(topLeftOffset.x, dlTopY),
                                size = Size(drawSize.width, dlH),
                                cornerRadius = cornerRadius
                            )
                        }
                    }
                } else {
                    val emptyColor = Color.Gray.copy(alpha = 0.3f)
                    if (isAmoled) {
                        drawRoundRect(
                            color = emptyColor,
                            topLeft = topLeftOffset,
                            size = drawSize,
                            cornerRadius = cornerRadius,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                        )
                    } else {
                        drawRoundRect(
                            color = emptyColor,
                            topLeft = topLeftOffset,
                            size = drawSize,
                            cornerRadius = cornerRadius
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(backgroundColor)
        ) {
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.bodyMedium, // Bigger font
                color = textColor,
                fontWeight = FontWeight.Bold, // Bold text
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatDetailRow(data: Pair<DataUsage, String>) {
    val (currentUsage, label) = data

    val (totalFmt, dlFmt, ulFmt) = remember(currentUsage) {
        Triple(
            formatBytes(currentUsage.rxBytes + currentUsage.txBytes),
            formatBytes(currentUsage.rxBytes),
            formatBytes(currentUsage.txBytes)
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = totalFmt,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                        (slideOutVertically { -it } + fadeOut())
            },
            label = "TotalUsageSwitch"
        ) { (v, u) ->
            Text(
                "$v $u",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AnimatedContent(dlFmt, label = "DLStat", transitionSpec = { fadeIn() togetherWith fadeOut() }) { (value, unit) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ArrowDownward, null, tint = ColorGraphDownload, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$value $unit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedContent(ulFmt, label = "ULStat", transitionSpec = { fadeIn() togetherWith fadeOut() }) { (value, unit) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ArrowUpward, null, tint = ColorGraphUpload, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$value $unit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}