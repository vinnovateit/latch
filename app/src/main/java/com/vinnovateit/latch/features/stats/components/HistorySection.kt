package com.vinnovateit.latch.features.stats.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.common.util.StatsColorPalettes
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.HistoryChartItem
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.formatDisplayDate
import com.vinnovateit.latch.core.stats.formatDurationDynamic
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Immutable
data class ChartDetailState(
    val usage: DataUsage,
    val label: String,
    val sessionCount: Int = 0,
    val durationFormatted: String = ""
)

@Composable
fun HistoryBarChart(
    history: List<HistoryChartItem>,
    isLoaded: Boolean = true,
    onSelectedDayChange: ((Long?) -> Unit)? = null
) {
    if (!isLoaded) {
        HistoryBarChartSkeleton()
    } else if (history.isNotEmpty()) {
        HistoryBarChartContent(chartItems = history, isLoaded = isLoaded, onSelectedDayChange = onSelectedDayChange)
    } else {
        NoDataCard("No stats available. Connect to Wi-Fi to start tracking your usage.")
    }
}

@Composable
private fun NoDataCard(msg: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            msg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryBarChartContent(
    chartItems: List<HistoryChartItem>,
    isLoaded: Boolean = true,
    onSelectedDayChange: ((Long?) -> Unit)? = null
) {

    if (isLoaded && chartItems.filterIsInstance<HistoryChartItem.BarData>().all { it.usage.rxBytes + it.usage.txBytes == 0L }) {
        NoDataCard("No stats available. Connect to Wi-Fi to start tracking your usage.")
        return
    }

    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current

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
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val (totalUsageDetail, overallMaxUsage) = remember(chartItems) {
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
        val detail = ChartDetailState(
            usage = DataUsage(rx, tx),
            label = "Total Data Usage",
            sessionCount = sessions,
            durationFormatted = formatDurationDynamic(durationMs)
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
                ChartDetailState(
                    usage = initialBarItem.usage,
                    label = initialBarItem.formattedDate.ifBlank {
                        formatDisplayDate(initialBarItem.timestamp)
                    },
                    sessionCount = initialBarItem.sessionCount,
                    durationFormatted = initialBarItem.durationFormatted
                )
            } else {
                totalUsageDetail
            }
        )
    }

    var visibleMaxUsage by remember { mutableLongStateOf(overallMaxUsage) }
    LaunchedEffect(chartItems, lazyListState) {
        snapshotFlow {
            val visible = lazyListState.layoutInfo.visibleItemsInfo
            var maxV = 1L
            for (i in 0 until visible.size) {
                val item = chartItems.getOrNull(visible[i].index)
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

    val animatedMaxUsage by animateFloatAsState(
        targetValue = visibleMaxUsage.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "BarChartMaxUsageSpring"
    )

    var lastCenteredIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(chartItems, lazyListState) {
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val visibleItems = layoutInfo.visibleItemsInfo
            var closestBarIdx = -1
            var minBarDist = Int.MAX_VALUE
            for (i in 0 until visibleItems.size) {
                val itemInfo = visibleItems[i]
                if (chartItems.getOrNull(itemInfo.index) is HistoryChartItem.BarData) {
                    val itemCenter = itemInfo.offset + itemInfo.size / 2
                    val dist = kotlin.math.abs(itemCenter - viewportCenter)
                    if (dist < minBarDist) {
                        minBarDist = dist
                        closestBarIdx = itemInfo.index
                    }
                }
            }
            if (closestBarIdx == -1 && visibleItems.isNotEmpty()) {
                var closestAny = -1
                var minAnyDist = Int.MAX_VALUE
                for (i in 0 until visibleItems.size) {
                    val itemInfo = visibleItems[i]
                    val itemCenter = itemInfo.offset + itemInfo.size / 2
                    val dist = kotlin.math.abs(itemCenter - viewportCenter)
                    if (dist < minAnyDist) {
                        minAnyDist = dist
                        closestAny = itemInfo.index
                    }
                }
                if (closestAny != -1) {
                    val prevBar = (closestAny downTo 0).firstOrNull { chartItems.getOrNull(it) is HistoryChartItem.BarData }
                    val nextBar = (closestAny until chartItems.size).firstOrNull { chartItems.getOrNull(it) is HistoryChartItem.BarData }
                    closestBarIdx = prevBar ?: nextBar ?: -1
                }
            }
            closestBarIdx
        }.distinctUntilChanged().collect { centerIdx ->
            if (centerIdx != -1 && centerIdx != lastCenteredIndex) {
                val item = chartItems.getOrNull(centerIdx) as? HistoryChartItem.BarData
                if (item != null) {
                    if (lazyListState.isScrollInProgress) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    lastCenteredIndex = centerIdx
                    selectedIndex = centerIdx
                    val formattedDate = item.formattedDate.ifBlank {
                        formatDisplayDate(item.timestamp)
                    }
                    displayedData = ChartDetailState(
                        usage = item.usage,
                        label = formattedDate,
                        sessionCount = item.sessionCount,
                        durationFormatted = item.durationFormatted
                    )
                    onSelectedDayChange?.invoke(item.timestamp)
                }
            }
        }
    }

    LaunchedEffect(lazyListState, chartItems) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress) {
                    val item = chartItems.getOrNull(selectedIndex) as? HistoryChartItem.BarData
                    if (item != null) {
                        onSelectedDayChange?.invoke(item.timestamp)
                    }
                }
            }
    }

    val chartPalette by SettingsManager.chartPalette.collectAsStateWithLifecycle()
    val (dlColor, ulColor) = StatsColorPalettes.resolveColors(chartPalette)

    val currentYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    val headerTitle = remember(chartItems, selectedIndex) {
        val selectedItem = chartItems.getOrNull(selectedIndex) as? HistoryChartItem.BarData
        val ts = selectedItem?.timestamp
            ?: chartItems.filterIsInstance<HistoryChartItem.BarData>().lastOrNull()?.timestamp
            ?: System.currentTimeMillis()
        val year = formatDate(ts, "yyyy").toIntOrNull() ?: currentYear
        val pattern = if (year == currentYear) "MMMM" else "MMMM yyyy"
        formatDate(ts, pattern)
    }

    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val barDataList = remember(chartItems) { chartItems.filterIsInstance<HistoryChartItem.BarData>() }
        val dataBars = remember(barDataList) {
            val withData = barDataList.filter { it.usage.rxBytes + it.usage.txBytes > 0L }
            if (withData.isNotEmpty()) withData else barDataList
        }
        val daysWithData = remember(barDataList) {
            barDataList.filter { it.usage.rxBytes + it.usage.txBytes > 0L }
                .map { formatDate(it.timestamp, "yyyy-MM-dd") }
                .toSet()
        }
        val minTs = remember(dataBars) { dataBars.minOfOrNull { it.timestamp } ?: System.currentTimeMillis() }
        val maxTs = remember(dataBars) { dataBars.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis() }

        val minYear = remember(minTs) {
            java.util.Calendar.getInstance().apply { timeInMillis = minTs }.get(java.util.Calendar.YEAR)
        }
        val maxYear = remember(maxTs) {
            java.util.Calendar.getInstance().apply { timeInMillis = maxTs }.get(java.util.Calendar.YEAR)
        }
        val minUtcMillis = remember(minTs) {
            val minCal = java.util.Calendar.getInstance().apply { timeInMillis = minTs }
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(minCal.get(java.util.Calendar.YEAR), minCal.get(java.util.Calendar.MONTH), minCal.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
            }.timeInMillis
        }
        val maxUtcMillis = remember(maxTs) {
            val maxCal = java.util.Calendar.getInstance().apply { timeInMillis = maxTs }
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(maxCal.get(java.util.Calendar.YEAR), maxCal.get(java.util.Calendar.MONTH), maxCal.get(java.util.Calendar.DAY_OF_MONTH), 23, 59, 59)
                set(java.util.Calendar.MILLISECOND, 999)
            }.timeInMillis
        }
        val effectiveYearRange = remember(minYear, maxYear) {
            minYear.coerceAtMost(maxYear)..maxYear.coerceAtLeast(minYear)
        }

        val selectedItem = chartItems.getOrNull(selectedIndex) as? HistoryChartItem.BarData
        val initialTs = selectedItem?.timestamp ?: maxTs
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialTs,
            yearRange = effectiveYearRange,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    if (utcTimeMillis !in minUtcMillis..maxUtcMillis) return false
                    if (daysWithData.isEmpty()) return true
                    val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = utcTimeMillis
                    }
                    val y = utcCal.get(java.util.Calendar.YEAR)
                    val m = utcCal.get(java.util.Calendar.MONTH) + 1
                    val d = utcCal.get(java.util.Calendar.DAY_OF_MONTH)
                    val dateKey = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m, d)
                    return daysWithData.contains(dateKey)
                }

                override fun isSelectableYear(year: Int): Boolean {
                    return year in effectiveYearRange
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val pickedUtcMillis = datePickerState.selectedDateMillis ?: return@TextButton
                        val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = pickedUtcMillis
                        }
                        val year = utcCal.get(java.util.Calendar.YEAR)
                        val month = utcCal.get(java.util.Calendar.MONTH)
                        val day = utcCal.get(java.util.Calendar.DAY_OF_MONTH)
                        val targetDayKey = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month + 1, day)

                        val localCal = java.util.Calendar.getInstance().apply {
                            set(year, month, day, 12, 0, 0)
                        }
                        val targetMillis = localCal.timeInMillis

                        var targetIdx = chartItems.indexOfFirst {
                            it is HistoryChartItem.BarData && formatDate(it.timestamp, "yyyy-MM-dd") == targetDayKey
                        }
                        if (targetIdx == -1) {
                            targetIdx = chartItems.indices
                                .filter { chartItems[it] is HistoryChartItem.BarData }
                                .minByOrNull { idx ->
                                    val itemTs = (chartItems[idx] as HistoryChartItem.BarData).timestamp
                                    kotlin.math.abs(itemTs - targetMillis)
                                } ?: -1
                        }

                        if (targetIdx != -1) {
                            val barItem = chartItems[targetIdx] as HistoryChartItem.BarData
                            selectedIndex = targetIdx
                            lastCenteredIndex = targetIdx
                            val formattedDate = barItem.formattedDate.ifBlank {
                                formatDisplayDate(barItem.timestamp)
                            }
                            displayedData = ChartDetailState(
                                usage = barItem.usage,
                                label = formattedDate,
                                sessionCount = barItem.sessionCount,
                                durationFormatted = barItem.durationFormatted
                            )
                            onSelectedDayChange?.invoke(barItem.timestamp)

                            coroutineScope.launch {
                                lazyListState.scrollToItem(targetIdx, 0)
                                val itemInfo = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == targetIdx }
                                if (itemInfo != null) {
                                    val viewportCenter = (lazyListState.layoutInfo.viewportStartOffset + lazyListState.layoutInfo.viewportEndOffset) / 2
                                    val itemCenter = itemInfo.offset + itemInfo.size / 2
                                    val delta = itemCenter - viewportCenter
                                    if (delta != 0) {
                                        lazyListState.scrollBy(delta.toFloat())
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showDatePicker = true }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = "Select Date",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val onBarTap: (Int, HistoryChartItem.BarData) -> Unit = remember(chartItems, density) {
            { idx, item ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                selectedIndex = idx
                lastCenteredIndex = idx
                val formattedDate = item.formattedDate.ifBlank {
                    formatDisplayDate(item.timestamp)
                }
                displayedData = ChartDetailState(
                    usage = item.usage,
                    label = formattedDate,
                    sessionCount = item.sessionCount,
                    durationFormatted = item.durationFormatted
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
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    } else {
                        val viewportWidth = layoutInfo.viewportSize.width
                        val barWidthPx = with(density) { 14.dp.roundToPx() }
                        val centeredOffset = (viewportWidth / 2) - (barWidthPx / 2)
                        lazyListState.animateScrollToItem(
                            index = idx,
                            scrollOffset = -centeredOffset
                        )
                    }
                }
            }
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
                verticalAlignment = Alignment.Bottom
            ) {
                itemsIndexed(
                    items = chartItems,
                    key = { index, item ->
                        when (item) {
                            is HistoryChartItem.BarData -> item.timestamp
                            is HistoryChartItem.MonthSeparator -> "month_${item.monthName}_$index"
                            is HistoryChartItem.CollapsedMonth -> "collapsed_${item.monthName}_$index"
                        }
                    },
                    contentType = { _, item ->
                        when (item) {
                            is HistoryChartItem.BarData -> "bar"
                            is HistoryChartItem.MonthSeparator -> "month"
                            is HistoryChartItem.CollapsedMonth -> "collapsed"
                        }
                    }
                ) { idx, item ->
                    when (item) {
                        is HistoryChartItem.BarData -> {
                            Bar(
                                modifier = Modifier
                                    .width(barWidth)
                                    .fillMaxHeight(),
                                usage = item.usage,
                                maxUsage = { animatedMaxUsage },
                                isSelected = { selectedIndex == idx },
                                hasSelection = { selectedIndex != -1 },
                                isAmoled = isAmoled,
                                barWidth = barWidth,
                                barAreaHeight = barAreaHeight,
                                dlColor = dlColor,
                                ulColor = ulColor,
                                onTap = { onBarTap(idx, item) }
                            )
                        }
                        is HistoryChartItem.MonthSeparator -> {
                            MonthSeparator(monthName = item.monthName)
                        }
                        is HistoryChartItem.CollapsedMonth -> {
                            MonthSeparator(monthName = item.monthName)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StatDetailRow(data = displayedData, dlColor = dlColor, ulColor = ulColor)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.rotate(-90f)
        )
    }
}

@Composable
private fun Bar(
    modifier: Modifier = Modifier,
    usage: DataUsage,
    maxUsage: () -> Float,
    isSelected: () -> Boolean,
    hasSelection: () -> Boolean = { false },
    isAmoled: Boolean = false,
    barWidth: Dp,
    barAreaHeight: Dp,
    dlColor: Color,
    ulColor: Color,
    onTap: () -> Unit
) {
    val total = usage.rxBytes + usage.txBytes
    val uploadFrac = if (total > 0) usage.txBytes.toFloat() / total.toFloat() else 0f
    val emptyColor = if (isAmoled) Color(0xFF262626) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    val density = LocalDensity.current
    val cornerRadius = remember(density) { with(density) { androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()) } }
    val emptyCornerRadius = remember(density) { with(density) { androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()) } }
    val strokeWidth = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidth) { androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth) }
    val minBarHeightPx = remember(density) { with(density) { 6.dp.toPx() } }
    val minUlHeightPx = remember(density) { with(density) { 2.dp.toPx() } }
    val emptyBarHeightPx = remember(density) { with(density) { 4.dp.toPx() } }

    Canvas(
        modifier = modifier
            .width(barWidth)
            .height(barAreaHeight)
            .graphicsLayer {
                alpha = if (isSelected()) 1f else if (hasSelection()) 0.45f else 1f
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            )
    ) {
        val maxVal = maxUsage()
        val currentFrac = if (maxVal > 0f && total > 0L) {
            val linearRatio = (total.toDouble() / maxVal.toDouble()).coerceIn(0.0, 1.0)
            val scaledRatio = kotlin.math.sqrt(linearRatio).toFloat()
            (0.04f + 0.92f * scaledRatio).coerceIn(0.04f, 0.96f)
        } else {
            0.04f
        }
        val inset = if (isAmoled) strokeWidth / 2 else 0f
        val drawWidth = (size.width - inset * 2).coerceAtLeast(0f)

        if (total > 0) {
            val rawBarHeight = (size.height * currentFrac).coerceAtLeast(minBarHeightPx)
            val drawHeight = (rawBarHeight - inset * 2).coerceAtLeast(0f)
            val startY = size.height - rawBarHeight + inset
            val topLeftOffset = Offset(inset, startY)

            val ulH = if (uploadFrac > 0f) (drawHeight * uploadFrac).coerceAtLeast(minUlHeightPx) else 0f
            val dlH = (drawHeight - ulH).coerceAtLeast(0f)

            // Upload on top
            if (ulH > 0f) {
                if (isAmoled) {
                    drawRoundRect(
                        color = ulColor,
                        topLeft = topLeftOffset,
                        size = Size(drawWidth, ulH),
                        cornerRadius = cornerRadius,
                        style = strokeStyle
                    )
                } else {
                    drawRoundRect(
                        color = ulColor,
                        topLeft = topLeftOffset,
                        size = Size(drawWidth, ulH),
                        cornerRadius = cornerRadius
                    )
                }
            }

            // Download below upload (zero gap)
            if (dlH > 0f) {
                val dlTopY = topLeftOffset.y + ulH
                if (isAmoled) {
                    drawRoundRect(
                        color = dlColor,
                        topLeft = Offset(topLeftOffset.x, dlTopY),
                        size = Size(drawWidth, dlH),
                        cornerRadius = cornerRadius,
                        style = strokeStyle
                    )
                } else {
                    drawRoundRect(
                        color = dlColor,
                        topLeft = Offset(topLeftOffset.x, dlTopY),
                        size = Size(drawWidth, dlH),
                        cornerRadius = cornerRadius
                    )
                }
            }
        } else {
            val drawHeight = (emptyBarHeightPx - inset * 2).coerceAtLeast(0f)
            val startY = size.height - emptyBarHeightPx + inset
            val topLeftOffset = Offset(inset, startY)
            drawRoundRect(
                color = emptyColor,
                topLeft = topLeftOffset,
                size = Size(drawWidth, drawHeight),
                cornerRadius = emptyCornerRadius
            )
        }
    }
}

@Composable
private fun StatDetailRow(
    data: ChartDetailState,
    dlColor: Color,
    ulColor: Color
) {
    val (currentUsage, label, _, durationFormatted) = data

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
        Text(
            "${totalFmt.first} ${totalFmt.second}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = dlColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "${dlFmt.first} ${dlFmt.second}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = ulColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "${ulFmt.first} ${ulFmt.second}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (durationFormatted.isNotBlank() && durationFormatted != "0s") {
                Text(
                    text = durationFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}