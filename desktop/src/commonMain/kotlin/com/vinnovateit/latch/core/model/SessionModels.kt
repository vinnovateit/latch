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

/**
 * The in-flight session.
 *
 * [liveData] is a *rolling window* of the most recent samples, not the whole
 * session: a sample lands every 2s, so retaining everything meant an 8-hour
 * session held ~14k points and -- because each tick rebuilt the list -- copied
 * all of them every 2 seconds. Nothing ever read more than the last 150 (the
 * home chart's width), so the running aggregates below carry what the trimmed
 * tail used to be summed for.
 */
data class LiveConnectionStatus(
    val startTimeMillis: Long,
    val liveData: List<LiveDataPoint>,
    /** Whole-session totals, including samples already dropped from [liveData]. */
    val totalRxBytes: Long = 0,
    val totalTxBytes: Long = 0,
    val maxRxBps: Long = 0,
    val maxTxBps: Long = 0,
)

data class SessionSummary(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val totalData: DataUsage,
    val history: List<LiveDataPoint>,
    val maxRxBps: Long,
    val maxTxBps: Long,
)
