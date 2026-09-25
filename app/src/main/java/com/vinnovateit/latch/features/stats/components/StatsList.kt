package com.vinnovateit.latch.features.stats.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.model.LiveConnectionStatus
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.features.stats.StatsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsList(
  modifier: Modifier = Modifier,
  isLive: Boolean,
  showSessionCard: Boolean = true,
  sessionToShow: SessionSummary?,
  portalHistory: List<PortalSessionRecord> = emptyList(),
  liveStatus: LiveConnectionStatus? = null,
  speedUnits: String,
  showAllSessions: Boolean,
  onToggleShowAll: () -> Unit,
  addSpacer: Boolean = false,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  onNavigateToHistory: () -> Unit = {},
  statsViewModel: StatsViewModel
) {
  val overviewMetrics by statsViewModel.overviewMetrics.collectAsStateWithLifecycle()
  val statsInsights by statsViewModel.statsInsights.collectAsStateWithLifecycle()
  val chartItems by statsViewModel.chartItems.collectAsStateWithLifecycle()
  val isHistoryLoaded by statsViewModel.isHistoryLoaded.collectAsStateWithLifecycle()
  val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
  val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current
  val chartPalette by SettingsManager.chartPalette.collectAsStateWithLifecycle()
  val (dlColor, ulColor) = com.vinnovateit.latch.common.util.StatsColorPalettes.resolveColors(chartPalette)
  val layoutDirection = LocalLayoutDirection.current

  var selectedTimestamp by remember(chartItems) { mutableStateOf<Long?>(null) }
  val selectedDaySessions = remember(portalHistory, selectedTimestamp) {
    val targetTs = selectedTimestamp ?: System.currentTimeMillis()
    val targetDayKey = formatDate(targetTs, "yyyy-MM-dd")
    portalHistory.filter { session ->
      session.loginTime > 0 &&
        formatDate(session.loginTime, "yyyy-MM-dd") == targetDayKey &&
        (session.uploadBytes > 0L || session.downloadBytes > 0L)
    }
  }

  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(
      top = contentPadding.calculateTopPadding(),
      bottom = contentPadding.calculateBottomPadding() + 100.dp,
      start = contentPadding.calculateStartPadding(layoutDirection),
      end = contentPadding.calculateEndPadding(layoutDirection)
    ),
    verticalArrangement = Arrangement.spacedBy(2.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    if (addSpacer) {
      item {
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    if (isLive && sessionToShow != null && showSessionCard) {
      item {
        Box(Modifier.height(250.dp)) {
          SessionCard(
            session = sessionToShow,
            speedUnit = speedUnits
          )
        }
        Spacer(modifier = Modifier.height(15.dp))
      }
    }

    item {
      StatsMetricsSummary(metrics = overviewMetrics)
      Spacer(modifier = Modifier.height(12.dp))
    }

    item {
      UsageInsightsCards(insights = statsInsights)
      Spacer(modifier = Modifier.height(16.dp))
    }

    if (chartItems.isNotEmpty() || !isHistoryLoaded) {
      item {
        HistoryBarChart(
          history = chartItems,
          isLoaded = isHistoryLoaded,
          onSelectedDayChange = { selectedTimestamp = it }
        )
        Spacer(modifier = Modifier.height(15.dp))
      }
    }

    if (selectedDaySessions.isNotEmpty()) {
      itemsIndexed(
        items = selectedDaySessions,
        key = { index, session -> "${session.loginTime}_${session.uploadBytes}_${session.downloadBytes}_$index" },
        contentType = { _, _ -> "session_item" }
      ) { index, session ->
        TodaySessionListItem(
          session = session,
          shape = groupedItemShape(index, selectedDaySessions.size),
          isAmoled = isAmoled,
          dlColor = dlColor,
          ulColor = ulColor
        )
      }
    }
  }
}