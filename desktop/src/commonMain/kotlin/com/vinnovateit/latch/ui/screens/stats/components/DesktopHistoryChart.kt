package com.vinnovateit.latch.ui.screens.stats.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
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
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.HistoryChartItem
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.formatDisplayDate
import com.vinnovateit.latch.core.stats.formatDurationDynamic
import com.vinnovateit.latch.ui.components.LatchIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Immutable
data class DesktopChartDetailState(
    val usage: DataUsage,
    val label: String,
    val sessionCount: Int = 0,
    val durationFormatted: String = "",
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryBarChart(
    chartItems: List<HistoryChartItem>,
    dlColor: Color,
    ulColor: Color,
    isAmoled: Boolean,
    onSelectedDayChange: ((Long) -> Unit)? = null,
) {
    if (chartItems.isEmpty()) return

    var isHovered by remember { mutableStateOf(false) }
    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (isHovered) 1f else 0f,
        animationSpec = tween(150),
        label = "DesktopChartScrollbarAlpha",
    )

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

    LaunchedEffect(initialBarItem) {
        initialBarItem?.let { onSelectedDayChange?.invoke(it.timestamp) }
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

    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val selectedItem = chartItems.getOrNull(selectedIndex) as? HistoryChartItem.BarData
        val initialTs = selectedItem?.timestamp ?: System.currentTimeMillis()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialTs,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val pickedUtcMillis = datePickerState.selectedDateMillis ?: return@TextButton
                        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = pickedUtcMillis
                        }
                        val year = utcCal.get(Calendar.YEAR)
                        val month = utcCal.get(Calendar.MONTH)
                        val day = utcCal.get(Calendar.DAY_OF_MONTH)
                        val targetDayKey = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)

                        val localCal = Calendar.getInstance().apply {
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
                            val formattedDate = barItem.formattedDate.ifBlank {
                                formatDisplayDate(barItem.timestamp)
                            }
                            displayedData = DesktopChartDetailState(
                                usage = barItem.usage,
                                label = formattedDate,
                                sessionCount = barItem.sessionCount,
                                durationFormatted = barItem.durationFormatted,
                            )
                            onSelectedDayChange?.invoke(barItem.timestamp)

                            coroutineScope.launch {
                                val layoutInfo = lazyListState.layoutInfo
                                val viewportWidth = layoutInfo.viewportSize.width
                                val barWidthPx = with(density) { 14.dp.roundToPx() }
                                val centeredOffset = (viewportWidth / 2) - (barWidthPx / 2)
                                lazyListState.animateScrollToItem(
                                    index = targetIdx,
                                    scrollOffset = -centeredOffset,
                                )
                            }
                        }
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showDatePicker = true }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Icon(
                    imageVector = LatchIcons.ArrowDropDown,
                    contentDescription = "Select Date",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isHovered = false },
        ) {
            val barWidth = if (maxWidth > 700.dp) 18.dp else 14.dp
            val barAreaHeight = if (maxWidth > 700.dp) 180.dp else 160.dp
            val rowHeight = barAreaHeight + 20.dp
            val centerPadding = ((maxWidth - barWidth) / 2).coerceIn(24.dp, 100.dp)

            Column(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val delta = event.changes.firstOrNull()?.scrollDelta
                            if (delta != null) {
                                val scrollAmount = if (delta.x != 0f) delta.x else delta.y
                                if (scrollAmount != 0f) {
                                    coroutineScope.launch {
                                        lazyListState.scrollBy(scrollAmount * 36f)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        },
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
                                        selectedIndex = idx
                                        onSelectedDayChange?.invoke(item.timestamp)
                                        displayedData = DesktopChartDetailState(
                                            usage = item.usage,
                                            label = item.formattedDate.ifBlank {
                                                formatDate(item.timestamp, "EEEE, MMMM d, yyyy")
                                            },
                                            sessionCount = item.sessionCount,
                                            durationFormatted = item.durationFormatted,
                                        )
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

                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = centerPadding, vertical = 4.dp)
                        .graphicsLayer { alpha = scrollbarAlpha },
                    style = defaultScrollbarStyle().copy(
                        unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    ),
                )
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
            val linearRatio = (total.toDouble() / maxVal.toDouble()).coerceIn(0.0, 1.0)
            val scaledRatio = kotlin.math.sqrt(linearRatio).toFloat()
            (0.04f + 0.92f * scaledRatio).coerceIn(0.04f, 0.96f)
        } else {
            0.04f
        }
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
            modifier = Modifier.height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (durationFormatted.isNotBlank() && durationFormatted != "0s") {
                Text(
                    text = durationFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
