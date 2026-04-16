package com.vinnovateit.latch.domain.model

data class DataUsage(
  val rxBytes: Long,
  val txBytes: Long,
  val rxBps: Long = rxBytes, // Default fallback prevents other files from breaking
  val txBps: Long = txBytes
)
data class LiveDataPoint(val timestamp: Long, val usage: DataUsage)
data class LiveConnectionStatus(val startTimeMillis: Long, val liveData: List<LiveDataPoint>)
data class SessionSummary(
  val startTimestamp: Long,
  val endTimestamp: Long,
  val totalData: DataUsage,
  val history: List<LiveDataPoint>,
  val maxRxBps: Long,
  val maxTxBps: Long
)
