package com.vinnovateit.latch.features.wifi.manager

import android.net.TrafficStats
import com.vinnovateit.latch.core.model.DataUsage
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
  private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var loggingJob: Job? = null

  // These track the bytes at the last polling interval to calculate the delta.
  private var lastTimestampRxBytes: Long = 0
  private var lastTimestampTxBytes: Long = 0
  
  // EMA tracking for speed smoothing
  private var lastSmoothedRxBps: Long = 0
  private var lastSmoothedTxBps: Long = 0

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
    val startTotalRx = TrafficStats.getTotalRxBytes()
    val startTotalTx = TrafficStats.getTotalTxBytes()
    val startMobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0)
    val startMobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0)

    val startRxBytes = startTotalRx - startMobileRx
    val startTxBytes = startTotalTx - startMobileTx

    if (startTotalRx == TrafficStats.UNSUPPORTED.toLong() || startTotalTx == TrafficStats.UNSUPPORTED.toLong()) {
      // Device does not support TrafficStats.
      return
    }

    lastTimestampRxBytes = startRxBytes
    lastTimestampTxBytes = startTxBytes
    lastSmoothedRxBps = 0L
    lastSmoothedTxBps = 0L
    lastPollTimeMs = System.currentTimeMillis() // Initialize time
    
    var currentDelay = 2000L
    
    loggingJob = loggerScope.launch {
      while (true) {
        delay(currentDelay)
        val currentTotalRx = TrafficStats.getTotalRxBytes()
        val currentTotalTx = TrafficStats.getTotalTxBytes()
        val currentMobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0)
        val currentMobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0)

        val currentRxBytes = currentTotalRx - currentMobileRx
        val currentTxBytes = currentTotalTx - currentMobileTx

        val currentTimeMs = System.currentTimeMillis()
        val intervalRx = (currentRxBytes - lastTimestampRxBytes).coerceAtLeast(0)
        val intervalTx = (currentTxBytes - lastTimestampTxBytes).coerceAtLeast(0)
        // Calculate exact elapsed time (prevent division by zero)
        val timeDeltaMs = (currentTimeMs - lastPollTimeMs).coerceAtLeast(1)
        // Calculate true Bytes Per Second
        val normalizedRxBps = (intervalRx * 1000L) / timeDeltaMs
        val normalizedTxBps = (intervalTx * 1000L) / timeDeltaMs
        
        // Exponential Moving Average to smooth out "shark spikes"
        // alpha = 0.4 means 40% new data, 60% historical (smooths out bursts)
        val alpha = 0.4
        val smoothedRxBps = if (lastSmoothedRxBps == 0L && normalizedRxBps > 0) {
            normalizedRxBps
        } else {
            (lastSmoothedRxBps * (1 - alpha) + normalizedRxBps * alpha).toLong()
        }
        
        val smoothedTxBps = if (lastSmoothedTxBps == 0L && normalizedTxBps > 0) {
            normalizedTxBps
        } else {
            (lastSmoothedTxBps * (1 - alpha) + normalizedTxBps * alpha).toLong()
        }
        
        // Natural EMA decay
        // If speed drops below 500 Bps (~0.5 KB/s), snap to 0 to prevent lingering micro-speeds
        val finalRxBps = if (smoothedRxBps < 500L && intervalRx == 0L) 0L else smoothedRxBps
        val finalTxBps = if (smoothedTxBps < 500L && intervalTx == 0L) 0L else smoothedTxBps

        _dataUsageFlow.value = DataUsage(
          rxBytes = intervalRx,
          txBytes = intervalTx,
          rxBps = finalRxBps,
          txBps = finalTxBps
        )
        
        // Slower ticking speed (2 seconds)
        currentDelay = 2000L

        // Update the baseline for the next interval.
        lastTimestampRxBytes = currentRxBytes
        lastTimestampTxBytes = currentTxBytes
        lastSmoothedRxBps = finalRxBps
        lastSmoothedTxBps = finalTxBps
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