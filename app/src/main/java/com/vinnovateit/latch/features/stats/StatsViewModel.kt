package com.vinnovateit.latch.features.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vinnovateit.latch.common.util.formatDate
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.features.stats.components.HistoryChartItem
import com.vinnovateit.latch.platform.LatchAppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class StatsViewModel(application: Application) : AndroidViewModel(application) {

  // Data now comes from the single source of truth: SessionRepository
  val liveStatus = LatchAppGraph.sessions.liveStatus
  val lastSession = LatchAppGraph.sessions.lastSession
  private val sessionHistory = LatchAppGraph.sessions.sessionSummaries

  // This flow combines live and last sessions to decide what to show in the UI.
  val sessionToShow: StateFlow<SessionSummary?> =
    combine(
      liveStatus,
      lastSession,
    ) { live, last ->
      live?.let {
        // Create a temporary summary for the UI from the live data
        SessionSummary(
          startTimestamp = it.startTimeMillis,
          endTimestamp = System.currentTimeMillis(), // It's ongoing
          totalData = DataUsage(it.totalRxBytes, it.totalTxBytes),
          history = it.liveData,
          maxRxBps = it.maxRxBps,
          maxTxBps = it.maxTxBps
        )
      } ?: last // If not live, show the last completed session
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val historyToShow: StateFlow<List<SessionSummary>> =
    combine(
      sessionHistory,
      liveStatus
    ) { history, live ->
      live?.let {
        val liveSummary = SessionSummary(
          startTimestamp = it.startTimeMillis,
          endTimestamp = System.currentTimeMillis(),
          totalData = DataUsage(it.totalRxBytes, it.totalTxBytes),
          history = it.liveData,
          maxRxBps = it.maxRxBps,
          maxTxBps = it.maxTxBps
        )
        val historyWithoutLive = history.filter { it.startTimestamp != liveSummary.startTimestamp }
        mergeSessions(listOf(liveSummary) + historyWithoutLive, 60_000L)
      } ?: mergeSessions(history, 60_000L)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private fun mergeSessions(sessions: List<SessionSummary>, gapMs: Long): List<SessionSummary> {
    if (sessions.isEmpty()) return emptyList()
    val sorted = sessions.sortedBy { it.startTimestamp }
    val merged = mutableListOf<SessionSummary>()
    var current = sorted[0]

    for (i in 1 until sorted.size) {
      val next = sorted[i]
      if (next.startTimestamp - current.endTimestamp <= gapMs) {
        current = current.copy(
          endTimestamp = maxOf(current.endTimestamp, next.endTimestamp),
          totalData = DataUsage(
            current.totalData.rxBytes + next.totalData.rxBytes,
            current.totalData.txBytes + next.totalData.txBytes
          ),
          history = current.history + next.history,
          maxRxBps = maxOf(current.maxRxBps, next.maxRxBps),
          maxTxBps = maxOf(current.maxTxBps, next.maxTxBps)
        )
      } else {
        merged.add(current)
        current = next
      }
    }
    merged.add(current)
    return merged.sortedByDescending { it.startTimestamp }
  }


  val chartItems: StateFlow<List<HistoryChartItem>> =
    historyToShow.map { sessions ->
      if (sessions.isEmpty()) return@map emptyList()

      val groupedByDay = sessions.groupBy {
        formatDate(it.startTimestamp, "yyyy-MM-dd")
      }.mapValues { (_, list) ->
        DataUsage(
          rxBytes = list.sumOf { it.totalData.rxBytes },
          txBytes = list.sumOf { it.totalData.txBytes }
        )
      }

      val today = Calendar.getInstance()
      val daysToShow = 7 // Always show the last 7 days

      val items = mutableListOf<HistoryChartItem>()
      var lastMonth = -1

      for (i in (daysToShow - 1) downTo 0) {
        val currentCal = Calendar.getInstance()
        currentCal.add(Calendar.DAY_OF_YEAR, -i)
        val dayTimestamp = currentCal.timeInMillis
        val key = formatDate(dayTimestamp, "yyyy-MM-dd")
        val usage = groupedByDay[key] ?: DataUsage(0, 0)

        val currentMonth = currentCal.get(Calendar.MONTH)
        if (lastMonth != -1 && currentMonth != lastMonth) {
          items.add(HistoryChartItem.MonthSeparator(formatDate(dayTimestamp, "MMM")))
        }
        lastMonth = currentMonth

        val label = formatDate(dayTimestamp, "E").first().toString()
        items.add(HistoryChartItem.BarData(usage, label, dayTimestamp))
      }
      items.distinct() // Ensure no duplicate separators if the week crosses a month boundary
    }.flowOn(Dispatchers.Default) // Perform mapping on a background thread
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())




  fun onClearHistory() {
    LatchAppGraph.sessions.clearHistory()
  }

  override fun onCleared() {
    super.onCleared()
  }
}