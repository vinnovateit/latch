package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.platform.ByteCounterSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls cumulative byte counters and emits smoothed throughput.
 *
 * The seam against the OS is [ByteCounterSource], deliberately placed *below*
 * the smoothing maths: the delta calculation, the bytes/sec normalisation, the
 * EMA and the noise gate are all lifted verbatim from the Android
 * TrafficStatsLogger so both platforms smooth identically. Only the counter
 * read differs (TrafficStats on Android, per-interface counters on desktop).
 *
 * Note the desktop source is actually *more* accurate: Android approximates
 * Wi-Fi as (total - mobile), whereas the desktop reads the interface directly.
 */
class ThroughputMonitor(
    private val source: ByteCounterSource,
    private val intervalMs: Long = 2000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loggingJob: Job? = null

    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastSmoothedRxBps: Long = 0
    private var lastSmoothedTxBps: Long = 0
    private var lastPollTimeMs: Long = 0

    private val _dataUsageFlow = MutableStateFlow(DataUsage(0, 0))
    val dataUsageFlow = _dataUsageFlow.asStateFlow()

    fun start() {
        if (loggingJob?.isActive == true) return

        val baseline = source.sample() ?: return

        lastRxBytes = baseline.rxBytes
        lastTxBytes = baseline.txBytes
        lastSmoothedRxBps = 0L
        lastSmoothedTxBps = 0L
        lastPollTimeMs = clock()

        loggingJob = scope.launch {
            while (isActive) {
                delay(intervalMs)

                val current = source.sample() ?: continue
                val currentTimeMs = clock()

                val intervalRx = (current.rxBytes - lastRxBytes).coerceAtLeast(0)
                val intervalTx = (current.txBytes - lastTxBytes).coerceAtLeast(0)
                // Guard against division by zero.
                val timeDeltaMs = (currentTimeMs - lastPollTimeMs).coerceAtLeast(1)

                // True BYTES per second (see DataUsage docs on the rxBps misnomer).
                val normalizedRxBps = (intervalRx * 1000L) / timeDeltaMs
                val normalizedTxBps = (intervalTx * 1000L) / timeDeltaMs

                // Exponential moving average smooths out burst "shark spikes".
                // alpha = 0.4 -> 40% new data, 60% history.
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

                // Snap sub-500 B/s to zero so EMA decay doesn't leave a ghost
                // trickle showing after traffic actually stops.
                val finalRxBps = if (smoothedRxBps < 500L && intervalRx == 0L) 0L else smoothedRxBps
                val finalTxBps = if (smoothedTxBps < 500L && intervalTx == 0L) 0L else smoothedTxBps

                _dataUsageFlow.value = DataUsage(
                    rxBytes = intervalRx,
                    txBytes = intervalTx,
                    rxBps = finalRxBps,
                    txBps = finalTxBps,
                )

                lastRxBytes = current.rxBytes
                lastTxBytes = current.txBytes
                lastSmoothedRxBps = finalRxBps
                lastSmoothedTxBps = finalTxBps
                lastPollTimeMs = currentTimeMs
            }
        }
    }

    fun stop() {
        loggingJob?.cancel()
        loggingJob = null
        _dataUsageFlow.value = DataUsage(0, 0)
    }
}
