package com.vinnovateit.latch.ui.screens.stats.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.model.AggregatedDayRecord
import com.vinnovateit.latch.core.model.DateRangeFilter
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.ui.components.LatchDetailHeader
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.core.mp.KCalendar as Calendar
import com.vinnovateit.latch.core.mp.nowMillis

val historyFilters = listOf(
    DateRangeFilter.ALL_TIME,
    DateRangeFilter.THIS_MONTH,
    DateRangeFilter.LAST_30_DAYS,
    DateRangeFilter.THIS_YEAR,
    DateRangeFilter.LAST_YEAR,
)

enum class HistorySortOption(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    HIGHEST_USAGE("Highest data"),
    LONGEST_DURATION("Longest duration"),
    MOST_SESSIONS("Most sessions"),
}

@Composable
fun NoDataCard(msg: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            msg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DesktopSessionHistoryView(
    allDayRecords: List<AggregatedDayRecord>,
    isSyncing: Boolean,
    dlColor: Color,
    ulColor: Color,
    isAmoled: Boolean,
    onBack: () -> Unit,
) {
    var selectedFilter by remember { mutableStateOf(DateRangeFilter.ALL_TIME) }
    var selectedSort by remember { mutableStateOf(HistorySortOption.NEWEST) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredSortedRecords = remember(allDayRecords, selectedFilter, selectedSort) {
        val now = nowMillis()
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
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val map = linkedMapOf<String, MutableList<AggregatedDayRecord>>()
        for (record in filteredSortedRecords) {
            val cal = Calendar.getInstance().apply { timeInMillis = record.dayTimestamp }
            val year = cal.get(Calendar.YEAR)
            val pattern = if (year == currentYear) "MMMM" else "MMMM yyyy"
            val title = formatDate(record.dayTimestamp, pattern)
            map.getOrPut(title) { mutableListOf() }.add(record)
        }
        map
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

    Column(modifier = Modifier.fillMaxSize()) {
        LatchDetailHeader(
            title = "Session History",
            onBack = onBack,
            actions = {
                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = LatchIcons.Sort,
                            contentDescription = "Sort sessions",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        HistorySortOption.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = sort.label,
                                        fontWeight = if (sort == selectedSort) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sort == selectedSort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    selectedSort = sort
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }
            },
        )

        val filterScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(filterScrollState)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val filters = historyFilters
            filters.forEachIndexed { index, filter ->
                ToggleButton(
                    checked = (filter == selectedFilter),
                    onCheckedChange = {
                        selectedFilter = filter
                    },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        filters.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    Text(filter.label)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        if (filteredSortedRecords.isEmpty()) {
            if (isSyncing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NoDataCard("No session history for the selected filter.")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        GameStatRow(
                            label = "Total data",
                            value = totalFormatted.first,
                            unit = totalFormatted.second,
                        )
                        GameStatRow(
                            label = "Portal sessions",
                            value = "$totalSessions",
                        )
                        GameStatRow(
                            label = "Active days",
                            value = "${filteredSortedRecords.size}",
                            unit = "days",
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
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = monthTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val monthTotalBytes = monthRecords.sumOf { it.totalBytes }
                            val (monthVal, monthUnit) = formatBytes(monthTotalBytes)
                            Text(
                                text = "$monthVal $monthUnit",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    itemsIndexed(
                        items = monthRecords,
                        key = { _, record -> "day_${record.dayTimestamp}" },
                    ) { index, record ->
                        DayAggregateListItem(
                            record = record,
                            shape = groupedItemShape(index, monthRecords.size),
                            isAmoled = isAmoled,
                            dlColor = dlColor,
                            ulColor = ulColor,
                        )
                    }
                }
            }
        }
    }
}
