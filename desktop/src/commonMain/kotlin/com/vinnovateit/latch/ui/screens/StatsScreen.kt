package com.vinnovateit.latch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.formatDisplayDate
import com.vinnovateit.latch.core.stats.generatePortalHtmlReport
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.stats_title
import com.vinnovateit.latch.ui.components.LatchDetailHeader
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.screens.stats.components.DesktopSessionHistoryView
import com.vinnovateit.latch.ui.screens.stats.components.HistoryBarChart
import com.vinnovateit.latch.ui.screens.stats.components.LiveSessionCard
import com.vinnovateit.latch.ui.screens.stats.components.StatsMetricsSummary
import com.vinnovateit.latch.ui.screens.stats.components.TodaySessionListItem
import com.vinnovateit.latch.ui.screens.stats.components.UsageInsightsCards
import com.vinnovateit.latch.ui.screens.stats.components.groupedItemShape
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import com.vinnovateit.latch.ui.theme.StatsColorPalettes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.io.File

enum class StatsSubScreen {
    Main,
    SessionHistory,
}

@Composable
fun StatsScreen(
    sessions: SessionRepository,
    platform: PlatformServices,
    onBack: (() -> Unit)?,
    onClearHistory: () -> Unit,
) {
    var currentSubScreen by remember { mutableStateOf(StatsSubScreen.Main) }

    val liveStatus by sessions.liveStatus.collectAsStateWithLifecycle()
    val portalHistory by sessions.portalHistory.collectAsStateWithLifecycle()
    val isSyncing by sessions.isSyncing.collectAsStateWithLifecycle()
    val speedUnit by SettingsManager.speedUnits.collectAsStateWithLifecycle()
    val chartPalette by SettingsManager.chartPalette.collectAsStateWithLifecycle()
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val (dlColor, ulColor) = StatsColorPalettes.resolveColors(chartPalette)

    LaunchedEffect(Unit) {
        val userId = platform.credentials.userId()
        val password = platform.credentials.password()
        if (!userId.isNullOrBlank() && !password.isNullOrBlank()) {
            sessions.syncPortalHistory(userId, password)
        }
    }

    val allDayRecords by sessions.aggregatedDayRecords.collectAsStateWithLifecycle()
    val metrics by sessions.overviewMetrics.collectAsStateWithLifecycle()
    val insights by sessions.statsInsights.collectAsStateWithLifecycle()
    val chartItems by sessions.chartItems.collectAsStateWithLifecycle()

    var selectedDayTimestamp by remember { mutableStateOf<Long?>(null) }
    val todayKey = remember { formatDate(System.currentTimeMillis(), "yyyy-MM-dd") }
    val selectedDateKey = selectedDayTimestamp?.let { formatDate(it, "yyyy-MM-dd") } ?: todayKey
    val isToday = selectedDateKey == todayKey

    val displayedSessions = remember(portalHistory, selectedDateKey) {
        portalHistory.filter { it.loginTime > 0 && (it.uploadBytes > 0L || it.downloadBytes > 0L) && formatDate(it.loginTime, "yyyy-MM-dd") == selectedDateKey }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    if (currentSubScreen == StatsSubScreen.SessionHistory) {
        DesktopSessionHistoryView(
            allDayRecords = allDayRecords,
            isSyncing = isSyncing,
            dlColor = dlColor,
            ulColor = ulColor,
            isAmoled = isAmoled,
            onBack = { currentSubScreen = StatsSubScreen.Main },
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            LatchDetailHeader(
                title = stringResource(Res.string.stats_title),
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = LatchIcons.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Session History") },
                                onClick = {
                                    menuExpanded = false
                                    currentSubScreen = StatsSubScreen.SessionHistory
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = LatchIcons.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export Full Report") },
                                onClick = {
                                    menuExpanded = false
                                    coroutineScope.launch {
                                        try {
                                            val userHome = System.getProperty("user.home") ?: "."
                                            val downloadsDir = File(userHome, "Downloads").takeIf { it.exists() && it.isDirectory }
                                                ?: File(userHome)
                                            val reportFile = File(downloadsDir, "latch-session-report-${System.currentTimeMillis()}.html")
                                            reportFile.outputStream().use { stream ->
                                                generatePortalHtmlReport(
                                                    sessions = portalHistory.filter { it.uploadBytes > 0L || it.downloadBytes > 0L },
                                                    outputStream = stream,
                                                    appVersion = "Desktop",
                                                    userId = platform.credentials.userId() ?: "",
                                                )
                                            }
                                            platform.systemActions.openUrl(reportFile.toURI().toString())
                                        } catch (e: Exception) {
                                            platform.logger.e("StatsScreen", "Failed to export HTML report", e)
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = LatchIcons.ExportNotes,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Resync History") },
                                onClick = {
                                    menuExpanded = false
                                    val userId = platform.credentials.userId()
                                    val password = platform.credentials.password()
                                    if (!userId.isNullOrBlank() && !password.isNullOrBlank()) {
                                        coroutineScope.launch {
                                            sessions.syncPortalHistory(userId, password, force = true)
                                        }
                                    } else {
                                        platform.logger.w("StatsScreen", "Cannot resync portal history: missing credentials")
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = LatchIcons.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        }
                    }
                },
            )

            if (liveStatus == null && allDayRecords.isEmpty()) {
                if (isSyncing && !sessions.isHistoryLoaded.value) {
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
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            Icon(
                                imageVector = LatchIcons.BarChart,
                                contentDescription = "Empty Stats Icon",
                                modifier = Modifier.size(96.dp),
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No stats available",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Connect to Wi-Fi to start tracking your data usage.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (maxWidth >= 840.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 1200.dp)
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            // Left pane: Live Session, Metrics, Insights
                            LazyColumn(
                                modifier = Modifier.weight(0.44f).fillMaxHeight(),
                                contentPadding = PaddingValues(bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                liveStatus?.let { live ->
                                    item {
                                        val usage = DataUsage(live.totalRxBytes, live.totalTxBytes)
                                        LiveSessionCard(
                                            startTimeMillis = live.startTimeMillis,
                                            usage = usage,
                                            latestRxBps = live.liveData.lastOrNull()?.usage?.rxBps ?: 0L,
                                            latestTxBps = live.liveData.lastOrNull()?.usage?.txBps ?: 0L,
                                            speedUnit = speedUnit,
                                            dlColor = dlColor,
                                            ulColor = ulColor,
                                        )
                                        Spacer(modifier = Modifier.height(15.dp))
                                    }
                                }

                                item {
                                    StatsMetricsSummary(
                                        metrics = metrics,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                item {
                                    UsageInsightsCards(insights = insights)
                                }
                            }

                            // Right pane: Chart + Selected day sessions
                            LazyColumn(
                                modifier = Modifier.weight(0.56f).fillMaxHeight(),
                                contentPadding = PaddingValues(bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (chartItems.isNotEmpty()) {
                                    item {
                                        HistoryBarChart(
                                            chartItems = chartItems,
                                            dlColor = dlColor,
                                            ulColor = ulColor,
                                            isAmoled = isAmoled,
                                            onSelectedDayChange = { selectedDayTimestamp = it },
                                        )
                                        Spacer(modifier = Modifier.height(15.dp))
                                    }
                                }

                                item {
                                    val title = if (isToday) "Today's Sessions" else "${formatDisplayDate(selectedDayTimestamp ?: System.currentTimeMillis())} Sessions"
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        textAlign = TextAlign.Left,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }

                                if (displayedSessions.isNotEmpty()) {
                                    itemsIndexed(
                                        items = displayedSessions,
                                        key = { index, session -> "${session.loginTime}_${session.uploadBytes}_${session.downloadBytes}_$index" },
                                        contentType = { _, _ -> "session_item" },
                                    ) { index, session ->
                                        TodaySessionListItem(
                                            session = session,
                                            shape = groupedItemShape(index, displayedSessions.size),
                                            isAmoled = isAmoled,
                                            dlColor = dlColor,
                                            ulColor = ulColor,
                                        )
                                    }
                                } else {
                                    item {
                                        val emptyText = if (isToday) "No active portal sessions recorded today." else "No active portal sessions recorded on ${formatDisplayDate(selectedDayTimestamp ?: System.currentTimeMillis())}."
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        ) {
                                            Text(
                                                text = emptyText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Narrow / Compact layout: single centered column
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp),
                            contentPadding = PaddingValues(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            liveStatus?.let { live ->
                                item {
                                    val usage = DataUsage(live.totalRxBytes, live.totalTxBytes)
                                    LiveSessionCard(
                                        startTimeMillis = live.startTimeMillis,
                                        usage = usage,
                                        latestRxBps = live.liveData.lastOrNull()?.usage?.rxBps ?: 0L,
                                        latestTxBps = live.liveData.lastOrNull()?.usage?.txBps ?: 0L,
                                        speedUnit = speedUnit,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                    )
                                    Spacer(modifier = Modifier.height(15.dp))
                                }
                            }

                            item {
                                StatsMetricsSummary(
                                    metrics = metrics,
                                    dlColor = dlColor,
                                    ulColor = ulColor,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            item {
                                UsageInsightsCards(insights = insights)
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            if (chartItems.isNotEmpty()) {
                                item {
                                    HistoryBarChart(
                                        chartItems = chartItems,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                        isAmoled = isAmoled,
                                        onSelectedDayChange = { selectedDayTimestamp = it },
                                    )
                                    Spacer(modifier = Modifier.height(15.dp))
                                }
                            }

                            item {
                                val title = if (isToday) "Today's Sessions" else "${formatDisplayDate(selectedDayTimestamp ?: System.currentTimeMillis())} Sessions"
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Left,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }

                            if (displayedSessions.isNotEmpty()) {
                                itemsIndexed(
                                    items = displayedSessions,
                                    key = { index, session -> "${session.loginTime}_${session.uploadBytes}_${session.downloadBytes}_$index" },
                                    contentType = { _, _ -> "session_item" },
                                ) { index, session ->
                                    TodaySessionListItem(
                                        session = session,
                                        shape = groupedItemShape(index, displayedSessions.size),
                                        isAmoled = isAmoled,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                    )
                                }
                            } else {
                                item {
                                    val emptyText = if (isToday) "No active portal sessions recorded today." else "No active portal sessions recorded on ${formatDisplayDate(selectedDayTimestamp ?: System.currentTimeMillis())}."
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    ) {
                                        Text(
                                            text = emptyText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
