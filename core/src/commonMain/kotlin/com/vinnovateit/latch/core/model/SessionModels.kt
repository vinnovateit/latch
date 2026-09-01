package com.vinnovateit.latch.core.model

/**
 * Ported verbatim from the Android app's WifiStatsManager.kt (a misleading
 * filename -- it contained only these data classes and no manager).
 *
 * NOTE on naming: rxBps/txBps hold **bytes** per second, not bits. The x8
 * conversion happens later in formatBitsPerSecond. The Android app has the same
 * misnomer; renaming would ripple through the graph code, the report generator
 * and the tray tooltip for no functional gain, so it is documented instead.
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
