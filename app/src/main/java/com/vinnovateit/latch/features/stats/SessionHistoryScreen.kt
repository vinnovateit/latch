package com.vinnovateit.latch.features.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.model.AggregatedDayRecord
import com.vinnovateit.latch.core.model.DateRangeFilter
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.features.stats.components.DayAggregateListItem
import com.vinnovateit.latch.features.stats.components.GameStatRow
import com.vinnovateit.latch.features.stats.components.SessionHistorySkeletonLoader
import com.vinnovateit.latch.features.stats.components.groupedItemShape
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import com.vinnovateit.latch.ui.theme.ModernizFontFamily
import java.util.Calendar

val historyFilters = listOf(
    DateRangeFilter.ALL_TIME,
    DateRangeFilter.THIS_MONTH,
    DateRangeFilter.LAST_30_DAYS,
    DateRangeFilter.THIS_YEAR,
    DateRangeFilter.LAST_YEAR
)

enum class HistorySortOption(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    HIGHEST_USAGE("Highest data"),
    LONGEST_DURATION("Longest duration"),
    MOST_SESSIONS("Most sessions")
}

fun formatMonthHeaderTitle(timestamp: Long, currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val year = cal.get(Calendar.YEAR)
    val pattern = if (year == currentYear) "MMMM" else "MMMM yyyy"
    return formatDate(timestamp, pattern)
}

fun groupRecordsByMonth(
    records: List<AggregatedDayRecord>,
    currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
): Map<String, List<AggregatedDayRecord>> {
    val map = linkedMapOf<String, MutableList<AggregatedDayRecord>>()
    for (record in records) {
        val title = formatMonthHeaderTitle(record.dayTimestamp, currentYear)
        map.getOrPut(title) { mutableListOf() }.add(record)
    }
    return map
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionHistoryScreen(
    onBackPressed: () -> Unit,
    statsViewModel: StatsViewModel
) {
    val allDayRecords by statsViewModel.allDayRecords.collectAsStateWithLifecycle()
    val isSyncing by statsViewModel.isSyncing.collectAsStateWithLifecycle()
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val backgroundColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.background
    val chartPalette by SettingsManager.chartPalette.collectAsStateWithLifecycle()
    val (dlColor, ulColor) = com.vinnovateit.latch.common.util.StatsColorPalettes.resolveColors(chartPalette)
    val haptic = LocalHapticFeedback.current

    var selectedFilter by remember { mutableStateOf(DateRangeFilter.ALL_TIME) }
    var selectedSort by remember { mutableStateOf(HistorySortOption.NEWEST) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredSortedRecords = remember(allDayRecords, selectedFilter, selectedSort) {
        val now = System.currentTimeMillis()
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        val curYear = nowCal.get(Calendar.YEAR)
        val curMonth = nowCal.get(Calendar.MONTH)
        val tempCal = Calendar.getInstance()

        val filtered = when (selectedFilter) {
            DateRangeFilter.ALL_TIME -> allDayRecords
            DateRangeFilter.THIS_MONTH -> allDayRecords.filter {
                tempCal.timeInMillis = it.dayTimestamp
                tempCal.get(Calendar.YEAR) == curYear && tempCal.get(Calendar.MONTH) == curMonth
            }
            DateRangeFilter.LAST_30_DAYS -> allDayRecords.filter {
                it.dayTimestamp >= (now - 30L * 86_400_000L)
            }
            DateRangeFilter.THIS_YEAR, DateRangeFilter.YTD -> allDayRecords.filter {
                tempCal.timeInMillis = it.dayTimestamp
                tempCal.get(Calendar.YEAR) == curYear
            }
            DateRangeFilter.LAST_YEAR -> allDayRecords.filter {
                tempCal.timeInMillis = it.dayTimestamp
                tempCal.get(Calendar.YEAR) == curYear - 1
            }
            else -> allDayRecords
        }

        when (selectedSort) {
            HistorySortOption.NEWEST -> filtered.sortedByDescending { it.dayTimestamp }
            HistorySortOption.OLDEST -> filtered.sortedBy { it.dayTimestamp }
            HistorySortOption.HIGHEST_USAGE -> filtered.sortedByDescending { it.totalBytes }
            HistorySortOption.LONGEST_DURATION -> filtered.sortedByDescending { it.totalDurationMillis }
            HistorySortOption.MOST_SESSIONS -> filtered.sortedByDescending { it.sessionCount }
        }
    }

    val groupedByMonth = remember(filteredSortedRecords) {
        groupRecordsByMonth(filteredSortedRecords)
    }

    val totalBytes = remember(filteredSortedRecords) {
        filteredSortedRecords.sumOf { it.totalBytes }
    }
    val totalSessions = remember(filteredSortedRecords) {
        filteredSortedRecords.sumOf { it.sessionCount }
    }
    val totalFormatted = remember(totalBytes) {
        formatBytes(totalBytes)
    }

    val lazyListState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = backgroundColor,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Session History",
                        fontFamily = ModernizFontFamily,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                },
                navigationIcon = {
                    FilledIconButton(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                            .clip(CircleShape),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBackPressed()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    Box(modifier = Modifier.padding(end = 8.dp)) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSortMenu = true
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = "Sort sessions",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            HistorySortOption.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = sort.label,
                                            fontWeight = if (sort == selectedSort) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sort == selectedSort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        selectedSort = sort
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = backgroundColor,
                    scrolledContainerColor = backgroundColor
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val filterScrollState = rememberScrollState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(filterScrollState)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters = historyFilters
                filters.forEachIndexed { index, filter ->
                    ToggleButton(
                        checked = (filter == selectedFilter),
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedFilter = filter
                        },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            filters.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.semantics { role = Role.RadioButton }
                    ) {
                        Text(filter.label)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (filteredSortedRecords.isEmpty()) {
                if (isSyncing) {
                    SessionHistorySkeletonLoader(
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No session history for the selected filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            GameStatRow(
                                label = "Total data",
                                value = totalFormatted.first,
                                unit = totalFormatted.second
                            )
                            GameStatRow(
                                label = "Portal sessions",
                                value = "$totalSessions"
                            )
                            GameStatRow(
                                label = "Active days",
                                value = "${filteredSortedRecords.size}",
                                unit = "days"
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    groupedByMonth.forEach { (monthTitle, monthRecords) ->
                        item(key = "month_header_$monthTitle") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = monthTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val monthTotalBytes = monthRecords.sumOf { it.totalBytes }
                                val (monthVal, monthUnit) = formatBytes(monthTotalBytes)
                                Text(
                                    text = "$monthVal $monthUnit",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        itemsIndexed(
                            items = monthRecords,
                            key = { _, record -> "day_${record.dayTimestamp}" }
                        ) { index, record ->
                            DayAggregateListItem(
                                record = record,
                                shape = groupedItemShape(index, monthRecords.size),
                                isAmoled = isAmoled,
                                dlColor = dlColor,
                                ulColor = ulColor
                            )
                        }
                    }
                }
            }
        }
    }
}
