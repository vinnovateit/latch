package com.vinnovateit.latch.features.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vinnovateit.latch.core.model.AggregatedDayRecord
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.HistoryChartItem
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.core.model.StatsOverviewMetrics
import com.vinnovateit.latch.core.stats.StatsInsights
import com.vinnovateit.latch.core.stats.computeChartItems
import com.vinnovateit.latch.core.stats.dayKey
import com.vinnovateit.latch.core.stats.nonZeroSessions
import com.vinnovateit.latch.core.stats.sessionsForDay
import com.vinnovateit.latch.platform.LatchAppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class StatsViewModel(application: Application) : AndroidViewModel(application) {

  val liveStatus = LatchAppGraph.sessions.liveStatus
  val lastSession = LatchAppGraph.sessions.lastSession

  val portalHistory: StateFlow<List<PortalSessionRecord>> = LatchAppGraph.sessions.portalHistory
  val isHistoryLoaded: StateFlow<Boolean> = LatchAppGraph.sessions.isHistoryLoaded
  val isSyncing: StateFlow<Boolean> = LatchAppGraph.sessions.isSyncing

  val nonZeroPortalHistory: StateFlow<List<PortalSessionRecord>> =
    portalHistory.map(::nonZeroSessions).flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  val todaySessions: StateFlow<List<PortalSessionRecord>> =
    nonZeroPortalHistory.map { list -> sessionsForDay(list, dayKey(System.currentTimeMillis())) }
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  val allDayRecords: StateFlow<List<AggregatedDayRecord>> = LatchAppGraph.sessions.aggregatedDayRecords
  val overviewMetrics: StateFlow<StatsOverviewMetrics> = LatchAppGraph.sessions.overviewMetrics
  val statsInsights: StateFlow<StatsInsights> = LatchAppGraph.sessions.statsInsights

  fun refreshHistory(force: Boolean = false) {
    LatchAppGraph.triggerHistorySync(force = force)
  }

  // Combines live and last sessions to determine what to show in the UI.
  val sessionToShow: StateFlow<SessionSummary?> =
    combine(liveStatus, lastSession) { live, last ->
      live?.let {
        SessionSummary(
          startTimestamp = it.startTimeMillis,
          endTimestamp = System.currentTimeMillis(),
          totalData = DataUsage(it.totalRxBytes, it.totalTxBytes),
          history = it.liveData,
          maxRxBps = it.maxRxBps,
          maxTxBps = it.maxTxBps
        )
      } ?: last
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

  val chartItems: StateFlow<List<HistoryChartItem>> =
    combine(nonZeroPortalHistory, liveStatus) { records, live ->
      computeChartItems(
        records,
        liveRxBytes = live?.totalRxBytes ?: 0L,
        liveTxBytes = live?.totalTxBytes ?: 0L
      )
    }.flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  fun onClearHistory() {
    LatchAppGraph.sessions.clearHistory()
  }
}