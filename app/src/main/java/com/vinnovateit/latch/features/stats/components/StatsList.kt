package com.vinnovateit.latch.features.stats.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.model.LiveConnectionStatus
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.formatDisplayDate
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
  var selectedDayTimestamp by remember { mutableStateOf<Long?>(null) }
  val todayKey = remember { formatDate(System.currentTimeMillis(), "yyyy-MM-dd") }
  val selectedDateKey = selectedDayTimestamp?.let { formatDate(it, "yyyy-MM-dd") } ?: todayKey
  val isToday = selectedDateKey == todayKey

  val displayedSessions = remember(portalHistory, selectedDateKey) {
    portalHistory.filter { session ->
      session.loginTime > 0 && (session.uploadBytes > 0L || session.downloadBytes > 0L) && formatDate(session.loginTime, "yyyy-MM-dd") == selectedDateKey
    }
  }

  val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
  val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current
  val chartPalette by SettingsManager.chartPalette.collectAsStateWithLifecycle()
  val (dlColor, ulColor) = com.vinnovateit.latch.common.util.StatsColorPalettes.resolveColors(chartPalette)
  val layoutDirection = LocalLayoutDirection.current

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
          onSelectedDayChange = { selectedDayTimestamp = it }
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
      itemsIndexed(displayedSessions, key = { index, session -> "session_${session.loginTime}_$index" }) { index, session ->
        TodaySessionListItem(
          session = session,
          shape = groupedItemShape(index, displayedSessions.size),
          isAmoled = isAmoled,
          dlColor = dlColor,
          ulColor = ulColor
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
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
          Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
          )
        }
      }
    }
  }
}