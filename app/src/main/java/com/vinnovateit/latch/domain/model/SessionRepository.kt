package com.vinnovateit.latch.domain.model

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.vinnovateit.latch.data.StatsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import com.vinnovateit.latch.data.LatchDatabase
import com.vinnovateit.latch.data.Session
import com.vinnovateit.latch.features.wifi.manager.TrafficStatsLogger
import com.vinnovateit.latch.features.wifi.quicksettings.LatchTileService
import com.vinnovateit.latch.features.wifi.widget.LatchWidgetUpdater
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Calendar
import kotlin.random.Random
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.LiveConnectionStatus
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.model.SessionSummary

object SessionRepository {
  // Bounds the in-memory per-point history kept for the live speed graph. At the ~2s poll
  // interval used by TrafficStatsLogger this is roughly an hour of history; session-end totals
  // (rx/tx bytes, max speeds) are tracked separately in LiveConnectionStatus and are NOT affected
  // by this cap.
  private const val MAX_LIVE_HISTORY_POINTS = 1800

  private var applicationContext: Application? = null
  private lateinit var statsDao: StatsDao
  private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var sessionUpdateJob: Job? = null
  private val isInitialized = AtomicBoolean(false)
  private var notificationSent = false
  // Atomically guards session start/stop so concurrent callers (network callback,
  // health check, login re-validation) can't both pass the "no session yet" check
  // and each start their own session against the same shared state.
  private val sessionActive = AtomicBoolean(false)

  private val _liveStatus = MutableStateFlow<LiveConnectionStatus?>(null)
  val liveStatus = _liveStatus.asStateFlow()

  private val _lastSession = MutableStateFlow<SessionSummary?>(null)
  val lastSession = _lastSession.asStateFlow()

  private val _sessionSummaries = MutableStateFlow<List<SessionSummary>>(emptyList())
  val sessionSummaries = _sessionSummaries.asStateFlow()

  fun initialize(context: Application) {
    if (isInitialized.getAndSet(true)) return
    applicationContext = context
    val database = LatchDatabase.getDatabase(context)
    statsDao = database.statsDao()
    loadSessionsFromDb()
  }

  private fun loadSessionsFromDb() {
    repoScope.launch {
      statsDao.getAllSessions().map { dbSessions ->
        dbSessions.map {
          SessionSummary(
            startTimestamp = it.startTime.time,
            endTimestamp = it.endTime.time,
            totalData = DataUsage(
              rxBytes = it.rxBytes,
              txBytes = it.txBytes
            ),
            history = emptyList(),
            maxRxBps = it.maxRxBps,
            maxTxBps = it.maxTxBps
          )
        }
      }.collect { summaries ->
        _sessionSummaries.value = summaries
        _lastSession.value = summaries.firstOrNull()
      }
    }
  }

  fun startSession(network: android.net.Network) {
    if (!sessionActive.compareAndSet(false, true)) return
    val context = applicationContext ?: run { sessionActive.set(false); return }
    notificationSent = false

    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    connectivityManager.bindProcessToNetwork(network)

    val startTime = System.currentTimeMillis()
    val initialStatus =
      LiveConnectionStatus(
        startTimeMillis = startTime,
        liveData = listOf(
          LiveDataPoint(
            startTime,
            DataUsage(0, 0)
          )
        )
      )
    _liveStatus.value = initialStatus
    TrafficStatsLogger.start()

    sessionUpdateJob = repoScope.launch {
      TrafficStatsLogger.dataUsageFlow.collect { dataUsage ->
        val currentStatus = _liveStatus.value
        if (currentStatus != null) {
          val newPoint =
            LiveDataPoint(
              System.currentTimeMillis(),
              dataUsage
            )
          val updatedStatus = currentStatus.copy(
            liveData = (currentStatus.liveData + newPoint).takeLast(MAX_LIVE_HISTORY_POINTS),
            totalRxBytes = currentStatus.totalRxBytes + dataUsage.rxBytes,
            totalTxBytes = currentStatus.totalTxBytes + dataUsage.txBytes,
            maxRxBps = maxOf(currentStatus.maxRxBps, dataUsage.rxBps),
            maxTxBps = maxOf(currentStatus.maxTxBps, dataUsage.txBps)
          )
          _liveStatus.value = updatedStatus
        }
      }
    }
    triggerWidgetUpdate()
    notifyTileUpdate()
  }

  fun stopSession() {
    if (!sessionActive.compareAndSet(true, false)) return
    val context = applicationContext ?: return
    val sessionToFinalize = _liveStatus.value ?: return

    sessionUpdateJob?.cancel()
    sessionUpdateJob = null
    TrafficStatsLogger.stop()

    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    connectivityManager.bindProcessToNetwork(null)
    _liveStatus.value = null

    val totalRxBytes = sessionToFinalize.totalRxBytes
    val totalTxBytes = sessionToFinalize.totalTxBytes
    val totalDataUsed = totalRxBytes + totalTxBytes
    val maxRxBps = sessionToFinalize.maxRxBps
    val maxTxBps = sessionToFinalize.maxTxBps

    if (totalDataUsed < 1024) {
      triggerWidgetUpdate()
      notifyTileUpdate()
      return
    }

    repoScope.launch {
      val session = Session(
        startTime = Date(sessionToFinalize.startTimeMillis),
        endTime = Date(System.currentTimeMillis()),
        rxBytes = totalRxBytes,
        txBytes = totalTxBytes,
        maxRxBps = maxRxBps,
        maxTxBps = maxTxBps
      )
      addSessionToDb(session)
    }
    triggerWidgetUpdate()
    notifyTileUpdate()
  }

  private suspend fun addSessionToDb(session: Session) {
    statsDao.insertSession(session)
  }

  fun clearHistory() {
    repoScope.launch {
      statsDao.clearAllSessions()
    }
  }

  // Developer Option to Fill Dummy Data
  fun generateDummyData() {
    repoScope.launch {
      val calendar = Calendar.getInstance()
      val sessions = mutableListOf<Session>()

      for (i in 0..6) {
        val dayCal = Calendar.getInstance()
        dayCal.add(Calendar.DAY_OF_YEAR, -i)

        // Generate 1-2 sessions per day for variety
        val sessionsCount = Random.nextInt(1, 3)

        for (j in 0 until sessionsCount) {
          // Random start time within that day (approximate)
          val durationSeconds = Random.nextLong(600, 7200) // 10 mins to 2 hours
          val startOffset = Random.nextLong(0, 3600 * 10) // Spread within 10 hours
          val startTimeMillis = dayCal.timeInMillis - (startOffset * 1000)
          val endTimeMillis = startTimeMillis + (durationSeconds * 1000)

          // Random Data Usage (High enough to be visible on graph)
          val rxBytes = Random.nextLong(50 * 1024 * 1024, 800 * 1024 * 1024) // 50MB - 800MB
          val txBytes = Random.nextLong(10 * 1024 * 1024, 200 * 1024 * 1024) // 10MB - 200MB

          // Calculate fake max speeds based on duration
          val maxRx = rxBytes / durationSeconds.coerceAtLeast(1) * 2 // Peak is roughly double avg
          val maxTx = txBytes / durationSeconds.coerceAtLeast(1) * 2

          sessions.add(
            Session(
              startTime = Date(startTimeMillis),
              endTime = Date(endTimeMillis),
              rxBytes = rxBytes,
              txBytes = txBytes,
              maxRxBps = maxRx,
              maxTxBps = maxTx
            )
          )
        }
      }

      sessions.forEach { addSessionToDb(it) }
    }
  }

  private fun triggerWidgetUpdate() {
    val context = applicationContext ?: return
    val workRequest = androidx.work.OneTimeWorkRequestBuilder<LatchWidgetUpdater>().build()
    androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
  }

  private fun notifyTileUpdate() {
    val context = applicationContext ?: return
    TileService.requestListeningState(
      context,
      ComponentName(context, LatchTileService::class.java)
    )
  }
}