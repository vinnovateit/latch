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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.stats.formatDisplayDate
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
import com.vinnovateit.latch.ui.stats.DesktopStatsSettings
import com.vinnovateit.latch.ui.stats.StatsEvent
import com.vinnovateit.latch.ui.stats.StatsNavigation
import com.vinnovateit.latch.ui.stats.StatsPresentationImpl
import com.vinnovateit.latch.ui.stats.StatsSubScreen
import com.vinnovateit.latch.ui.stats.StatsUiState
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import com.vinnovateit.latch.ui.theme.StatsColorPalettes
import com.vinnovateit.latch.ui.theme.satoshiFontFamily
import org.jetbrains.compose.resources.stringResource
import com.vinnovateit.latch.core.mp.nowMillis

/**
 * Narrow view: renders [StatsUiState] and forwards events to a remembered
 * presentation module. No domain calls, no scope and no derived state live here.
 */
@Composable
fun StatsScreen(
    sessions: SessionRepository,
    platform: PlatformServices,
    onBack: (() -> Unit)?,
    onClearHistory: () -> Unit,
) {
    // Host callbacks change identity on every parent recomposition. Route them
    // through a stable adapter so the remembered module keeps its state.
    val navigation = remember { StatsNavigation() }
    navigation.onBack = onBack
    navigation.onClearHistory = onClearHistory

    val presentation = remember(sessions, platform) {
        StatsPresentationImpl(sessions, platform, DesktopStatsSettings, navigation)
    }
    DisposableEffect(presentation) {
        onDispose { presentation.close() }
    }
    // Same one-shot behaviour as the old LaunchedEffect(Unit) sync.
    LaunchedEffect(presentation) {
        presentation.onEvent(StatsEvent.RefreshHistory)
    }

    val uiState by presentation.uiState.collectAsStateWithLifecycle()
    StatsScreenContent(uiState = uiState, onEvent = presentation::onEvent, onBack = onBack)
}

@Composable
private fun StatsScreenContent(
    uiState: StatsUiState,
    onEvent: (StatsEvent) -> Unit,
    onBack: (() -> Unit)?,
) {
    val isAmoled = uiState.usePureBlack && LocalIsDarkTheme.current
    val (dlColor, ulColor) = StatsColorPalettes.resolveColors(uiState.chartPalette)

    if (uiState.currentSubScreen == StatsSubScreen.SessionHistory) {
        DesktopSessionHistoryView(
            allDayRecords = uiState.allDayRecords,
            isSyncing = uiState.isSyncing,
            dlColor = dlColor,
            ulColor = ulColor,
            isAmoled = isAmoled,
            onBack = { onEvent(StatsEvent.CloseSessionHistory) },
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            LatchDetailHeader(
                title = stringResource(Res.string.stats_title),
                onBack = onBack?.let { { onEvent(StatsEvent.Back) } },
                actions = {
                    Box {
                        IconButton(
                            onClick = { onEvent(StatsEvent.SetMenuExpanded(true)) },
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
                            expanded = uiState.menuExpanded,
                            onDismissRequest = { onEvent(StatsEvent.SetMenuExpanded(false)) },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Session History") },
                                onClick = {
                                    onEvent(StatsEvent.SetMenuExpanded(false))
                                    onEvent(StatsEvent.OpenSessionHistory)
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
                                    onEvent(StatsEvent.SetMenuExpanded(false))
                                    onEvent(StatsEvent.ExportReport)
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
                                    onEvent(StatsEvent.SetMenuExpanded(false))
                                    onEvent(StatsEvent.ResyncHistory)
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

            if (uiState.liveStatus == null && uiState.allDayRecords.isEmpty()) {
                if (uiState.isSyncing && !uiState.isHistoryLoaded) {
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
                                fontFamily = satoshiFontFamily(FontWeight.Bold),
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
                                uiState.liveStatus?.let { live ->
                                    item {
                                        val usage = DataUsage(live.totalRxBytes, live.totalTxBytes)
                                        LiveSessionCard(
                                            startTimeMillis = live.startTimeMillis,
                                            usage = usage,
                                            latestRxBps = live.liveData.lastOrNull()?.usage?.rxBps ?: 0L,
                                            latestTxBps = live.liveData.lastOrNull()?.usage?.txBps ?: 0L,
                                            speedUnit = uiState.speedUnit,
                                            dlColor = dlColor,
                                            ulColor = ulColor,
                                        )
                                        Spacer(modifier = Modifier.height(15.dp))
                                    }
                                }

                                item {
                                    StatsMetricsSummary(
                                        metrics = uiState.metrics,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                item {
                                    UsageInsightsCards(insights = uiState.insights)
                                }
                            }

                            // Right pane: Chart + Selected day sessions
                            LazyColumn(
                                modifier = Modifier.weight(0.56f).fillMaxHeight(),
                                contentPadding = PaddingValues(bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (uiState.chartItems.isNotEmpty()) {
                                    item {
                                        HistoryBarChart(
                                            chartItems = uiState.chartItems,
                                            dlColor = dlColor,
                                            ulColor = ulColor,
                                            isAmoled = isAmoled,
                                            onSelectedDayChange = { onEvent(StatsEvent.SelectDay(it)) },
                                        )
                                        Spacer(modifier = Modifier.height(15.dp))
                                    }
                                }

                                item {
                                    val title = if (uiState.isToday) "Today's Sessions" else "${formatDisplayDate(uiState.selectedDayTimestamp ?: nowMillis())} Sessions"
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = satoshiFontFamily(FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onBackground,
                                        textAlign = TextAlign.Left,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }

                                if (uiState.displayedSessions.isNotEmpty()) {
                                    itemsIndexed(
                                        items = uiState.displayedSessions,
                                        key = { index, session -> "${session.loginTime}_${session.uploadBytes}_${session.downloadBytes}_$index" },
                                        contentType = { _, _ -> "session_item" },
                                    ) { index, session ->
                                        TodaySessionListItem(
                                            session = session,
                                            shape = groupedItemShape(index, uiState.displayedSessions.size),
                                            isAmoled = isAmoled,
                                            dlColor = dlColor,
                                            ulColor = ulColor,
                                        )
                                    }
                                } else {
                                    item {
                                        val emptyText = if (uiState.isToday) "No active portal sessions recorded today." else "No active portal sessions recorded on ${formatDisplayDate(uiState.selectedDayTimestamp ?: nowMillis())}."
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
                            uiState.liveStatus?.let { live ->
                                item {
                                    val usage = DataUsage(live.totalRxBytes, live.totalTxBytes)
                                    LiveSessionCard(
                                        startTimeMillis = live.startTimeMillis,
                                        usage = usage,
                                        latestRxBps = live.liveData.lastOrNull()?.usage?.rxBps ?: 0L,
                                        latestTxBps = live.liveData.lastOrNull()?.usage?.txBps ?: 0L,
                                        speedUnit = uiState.speedUnit,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                    )
                                    Spacer(modifier = Modifier.height(15.dp))
                                }
                            }

                            item {
                                StatsMetricsSummary(
                                    metrics = uiState.metrics,
                                    dlColor = dlColor,
                                    ulColor = ulColor,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            item {
                                UsageInsightsCards(insights = uiState.insights)
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            if (uiState.chartItems.isNotEmpty()) {
                                item {
                                    HistoryBarChart(
                                        chartItems = uiState.chartItems,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                        isAmoled = isAmoled,
                                        onSelectedDayChange = { onEvent(StatsEvent.SelectDay(it)) },
                                    )
                                    Spacer(modifier = Modifier.height(15.dp))
                                }
                            }

                            item {
                                val title = if (uiState.isToday) "Today's Sessions" else "${formatDisplayDate(uiState.selectedDayTimestamp ?: nowMillis())} Sessions"
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = satoshiFontFamily(FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Left,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }

                            if (uiState.displayedSessions.isNotEmpty()) {
                                itemsIndexed(
                                    items = uiState.displayedSessions,
                                    key = { index, session -> "${session.loginTime}_${session.uploadBytes}_${session.downloadBytes}_$index" },
                                    contentType = { _, _ -> "session_item" },
                                ) { index, session ->
                                    TodaySessionListItem(
                                        session = session,
                                        shape = groupedItemShape(index, uiState.displayedSessions.size),
                                        isAmoled = isAmoled,
                                        dlColor = dlColor,
                                        ulColor = ulColor,
                                    )
                                }
                            } else {
                                item {
                                    val emptyText = if (uiState.isToday) "No active portal sessions recorded today." else "No active portal sessions recorded on ${formatDisplayDate(uiState.selectedDayTimestamp ?: nowMillis())}."
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
