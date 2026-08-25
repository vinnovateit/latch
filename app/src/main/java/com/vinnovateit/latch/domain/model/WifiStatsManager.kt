package com.vinnovateit.latch.domain.model

data class DataUsage(
  val rxBytes: Long,
  val txBytes: Long,
  val rxBps: Long = rxBytes, // Default fallback prevents other files from breaking
  val txBps: Long = txBytes
)
data class LiveDataPoint(val timestamp: Long, val usage: DataUsage)
data class LiveConnectionStatus(
  val startTimeMillis: Long,
  val liveData: List<LiveDataPoint>,
  // Running totals across the whole session, tracked independently of `liveData` since that
  // list is capped to a trailing window (see SessionRepository.MAX_LIVE_HISTORY_POINTS) and can
  // no longer be summed/maxed directly once older points have been dropped.
  val totalRxBytes: Long = 0,
  val totalTxBytes: Long = 0,
  val maxRxBps: Long = 0,
  val maxTxBps: Long = 0
)
data class SessionSummary(
  val startTimestamp: Long,
  val endTimestamp: Long,
  val totalData: DataUsage,
  val history: List<LiveDataPoint>,
  val maxRxBps: Long,
  val maxTxBps: Long
)
