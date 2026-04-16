package com.vinnovateit.latch.features.wifi.manager

import android.net.TrafficStats
import com.vinnovateit.latch.domain.model.DataUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A singleton object responsible for the single task of polling
 * TrafficStats and emitting the data usage in intervals.
 */
object TrafficStatsLogger {
  private const val POLLING_INTERVAL_MS = 1000L
  private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var loggingJob: Job? = null

  // These track the bytes at the last polling interval to calculate the delta.
  private var lastTimestampRxBytes: Long = 0
  private var lastTimestampTxBytes: Long = 0
  private var lastPollTimeMs: Long = 0 // Tracks exact time
  // This flow emits the data usage (delta) for each interval.
  private val _dataUsageFlow = MutableStateFlow(DataUsage(0, 0))
  val dataUsageFlow = _dataUsageFlow.asStateFlow()

  /**
   * Starts the periodic polling of traffic stats.
   */
  fun start() {
    if (loggingJob?.isActive == true) return

    // Get the initial total bytes to use as a baseline.
    val startRxBytes = TrafficStats.getTotalRxBytes()
    val startTxBytes = TrafficStats.getTotalTxBytes()

    if (startRxBytes == TrafficStats.UNSUPPORTED.toLong() || startTxBytes == TrafficStats.UNSUPPORTED.toLong()) {
      // Device does not support TrafficStats.
      return
    }

    lastTimestampRxBytes = startRxBytes
    lastTimestampTxBytes = startTxBytes
    lastPollTimeMs = System.currentTimeMillis() // Initialize time
    loggingJob = loggerScope.launch {
      while (true) {
        delay(POLLING_INTERVAL_MS)
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentTimeMs = System.currentTimeMillis()
        val intervalRx = (currentRxBytes - lastTimestampRxBytes).coerceAtLeast(0)
        val intervalTx = (currentTxBytes - lastTimestampTxBytes).coerceAtLeast(0)
        // Calculate exact elapsed time (prevent division by zero)
        val timeDeltaMs = (currentTimeMs - lastPollTimeMs).coerceAtLeast(1)
        // Calculate true Bytes Per Second
        val normalizedRxBps = (intervalRx * 1000L) / timeDeltaMs
        val normalizedTxBps = (intervalTx * 1000L) / timeDeltaMs
        _dataUsageFlow.value = DataUsage(
          rxBytes = intervalRx,
          txBytes = intervalTx,
          rxBps = normalizedRxBps,
          txBps = normalizedTxBps
        )

        // Update the baseline for the next interval.
        lastTimestampRxBytes = currentRxBytes
        lastTimestampTxBytes = currentTxBytes
        lastPollTimeMs = currentTimeMs // Update time
      }
    }
  }

  /**
   * Stops polling for traffic stats.
   */
  fun stop() {
    loggingJob?.cancel()
    loggingJob = null
    _dataUsageFlow.value = DataUsage(0, 0) // Reset flow to zero
  }
}