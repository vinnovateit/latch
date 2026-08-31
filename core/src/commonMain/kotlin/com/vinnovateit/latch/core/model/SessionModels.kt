package com.vinnovateit.latch.core.model

/**
 * Common session and telemetry models shared across Android and Desktop.
 *
 * NOTE on naming: rxBps/txBps hold bytes per second, not bits. The x8 conversion
 * happens in formatBitsPerSecond.
 */
data class DataUsage(
    val rxBytes: Long,
    val txBytes: Long,
    val rxBps: Long = rxBytes,
    val txBps: Long = txBytes,
)

data class LiveDataPoint(val timestamp: Long, val usage: DataUsage)

data class LiveConnectionStatus(
    val startTimeMillis: Long,
    val liveData: List<LiveDataPoint>,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val maxRxBps: Long = 0L,
    val maxTxBps: Long = 0L,
)

data class SessionSummary(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val totalData: DataUsage,
    val history: List<LiveDataPoint>,
    val maxRxBps: Long,
    val maxTxBps: Long,
)
