package com.vinnovateit.latch.core.domain

import com.vinnovateit.latch.core.data.Session
import com.vinnovateit.latch.core.data.StatsDao
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.LiveConnectionStatus
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tracks the live session and persists finished ones.
 *
 * Ported from the Android singleton, with the Application context, the
 * WorkManager widget enqueue and the TileService nudge all removed. The
 * bindProcessToNetwork calls are gone too -- pinning an entire desktop JVM's
 * traffic to one NIC for the length of a session would be wrong. Notifying the
 * tray happens through [onSessionChanged] instead.
 */
class SessionRepository(
    private val statsDao: StatsDao,
    private val throughput: ThroughputMonitor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private companion object {
        /**
         * Samples kept in memory for the live chart. The home chart draws at most
         * 150 points, so anything older is retained by the running totals in
         * [LiveConnectionStatus] instead of by the list.
         */
        const val LIVE_WINDOW = 150
    }

    private var sessionUpdateJob: Job? = null

    /** Fired when a session starts or ends, so the tray can refresh. */
    var onSessionChanged: (() -> Unit)? = null

    private val _liveStatus = MutableStateFlow<LiveConnectionStatus?>(null)
    val liveStatus = _liveStatus.asStateFlow()

    private val _sessionSummaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessionSummaries = _sessionSummaries.asStateFlow()

    private val _lastSession = MutableStateFlow<SessionSummary?>(null)
    val lastSession = _lastSession.asStateFlow()

    fun initialize() {
        scope.launch {
            statsDao.getAllSessions()
                .map { rows ->
                    rows.map { row ->
                        SessionSummary(
                            startTimestamp = row.startTime,
                            endTimestamp = row.endTime,
                            totalData = DataUsage(rxBytes = row.rxBytes, txBytes = row.txBytes),
                            history = emptyList(),
                            maxRxBps = row.maxRxBps,
                            maxTxBps = row.maxTxBps,
                        )
                    }
                }
                .collect { summaries ->
                    _sessionSummaries.value = summaries
                    _lastSession.value = summaries.firstOrNull()
                }
        }
    }

    fun startSession() {
        if (sessionUpdateJob?.isActive == true || _liveStatus.value != null) return

        val startTime = System.currentTimeMillis()
        _liveStatus.value = LiveConnectionStatus(
            startTimeMillis = startTime,
            liveData = listOf(LiveDataPoint(startTime, DataUsage(0, 0))),
        )
        throughput.start()

        sessionUpdateJob = scope.launch {
            throughput.dataUsageFlow.collect { usage ->
                val current = _liveStatus.value ?: return@collect
                val point = LiveDataPoint(System.currentTimeMillis(), usage)
                // Drop the oldest sample once the window is full rather than
                // growing without bound -- the aggregates below keep the parts
                // of the session the list no longer holds.
                val window = current.liveData
                    .let { if (it.size >= LIVE_WINDOW) it.subList(it.size - LIVE_WINDOW + 1, it.size) else it }
                _liveStatus.value = current.copy(
                    liveData = window + point,
                    totalRxBytes = current.totalRxBytes + usage.rxBytes,
                    totalTxBytes = current.totalTxBytes + usage.txBytes,
                    maxRxBps = maxOf(current.maxRxBps, usage.rxBps),
                    maxTxBps = maxOf(current.maxTxBps, usage.txBps),
                )
            }
        }
        onSessionChanged?.invoke()
    }

    fun stopSession() {
        val sessionToFinalize = _liveStatus.value ?: return

        sessionUpdateJob?.cancel()
        sessionUpdateJob = null
        throughput.stop()
        _liveStatus.value = null

        // Read the running aggregates, not the sample list: the list is a
        // rolling window and no longer covers the whole session.
        val totalRxBytes = sessionToFinalize.totalRxBytes
        val totalTxBytes = sessionToFinalize.totalTxBytes
        val maxRxBps = sessionToFinalize.maxRxBps
        val maxTxBps = sessionToFinalize.maxTxBps

        // Discard trivial sessions so the history isn't polluted by a connect
        // that carried no traffic. Threshold matches Android.
        if (totalRxBytes + totalTxBytes < 1024) {
            onSessionChanged?.invoke()
            return
        }

        scope.launch {
            statsDao.insertSession(
                Session(
                    startTime = sessionToFinalize.startTimeMillis,
                    endTime = System.currentTimeMillis(),
                    rxBytes = totalRxBytes,
                    txBytes = totalTxBytes,
                    maxRxBps = maxRxBps,
                    maxTxBps = maxTxBps,
                )
            )
        }
        onSessionChanged?.invoke()
    }

    fun clearHistory() {
        scope.launch { statsDao.clearAllSessions() }
    }
}
